package in.dexter.ledger.app;

import in.dexter.contracts.events.ledger.ExpenseChangedV1;
import in.dexter.contracts.events.ledger.ImportCompletedV1;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import in.dexter.ledger.app.ImportStore.Claim;
import in.dexter.ledger.app.ImportStore.RowError;
import in.dexter.ledger.app.ImportStore.StagedRow;
import in.dexter.ledger.domain.DailyDelta;
import in.dexter.ledger.domain.ImportRow;
import in.dexter.ledger.domain.PaymentMethod;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one claimed import job (MASTER_PLAN P08):
 * <ol>
 *   <li>stage: COPY the CSV from the object store into a temporary table and read it back (transaction 1);</li>
 *   <li>validate every row ({@link ImportRow}); give rows without a {@code client_ref} a content-derived one;</li>
 *   <li>resolve the merchants (Redis-cached alias stages, outside any transaction);</li>
 *   <li>merge (transaction 2): multi-row INSERT skipping existing client references, daily totals per bucket, one
 *       batched outbox insert of {@code ledger.expense.created} events, the row errors, the job's final counts and
 *       {@code ledger.import.completed}.</li>
 * </ol>
 * Importing the same file again creates nothing: every row's reference already exists ({@code duplicates}). A worker
 * that dies mid-way leaves the job {@code running}; it is claimed again after the stale period (A-50).
 */
public final class ImportProcessor {
  private static final Logger log = LoggerFactory.getLogger(ImportProcessor.class);
  /** Row errors stored per job (all are counted). */
  public static final int MAX_STORED_ERRORS = 1000;

  public record Settings(long maxBytes, int maxRows, Duration stale, int maxAttempts) {}

  private final TenantTx tx;
  private final ImportStore store;
  private final ImportFiles files;
  private final ExpenseStore expenses;
  private final DailyTotalsStore daily;
  private final CategoryStore categories;
  private final OutboxWriter outbox;
  private final MerchantResolver resolver;
  private final Clock clock;
  private final Settings settings;

  public ImportProcessor(TenantTx tx, ImportStore store, ImportFiles files, ExpenseStore expenses,
      DailyTotalsStore daily, CategoryStore categories, OutboxWriter outbox, MerchantResolver resolver, Clock clock,
      Settings settings) {
    this.tx = tx;
    this.store = store;
    this.files = files;
    this.expenses = expenses;
    this.daily = daily;
    this.categories = categories;
    this.outbox = outbox;
    this.resolver = resolver;
    this.clock = clock;
    this.settings = settings;
  }

  /** Claims and runs the oldest waiting job; empty when there was none. */
  public Optional<UUID> runOnce() {
    Optional<Claim> claim = store.claim(settings.stale(), settings.maxAttempts());
    claim.ifPresent(this::process);
    return claim.map(Claim::jobId);
  }

  void process(Claim c) {
    Tenant tenant = Tenant.user(c.accountId());
    long t0 = System.nanoTime();
    List<StagedRow> staged;
    try (InputStream in = files.open(c.fileKey())) {
      staged = tx.write(tenant, () -> store.stage(in, settings.maxBytes(), settings.maxRows()));
    } catch (ImportFiles.Missing e) {
      fail(tenant, c.jobId(), "FILE_MISSING", "No uploaded file: PUT it to the upload URL, then start the import");
      return;
    } catch (ImportStore.InvalidFile e) {
      fail(tenant, c.jobId(), e.code(), e.getMessage());
      return;
    } catch (IOException | RuntimeException e) {
      log.warn("IMPORT_STAGE_FAILED job={} attempt={} error={}", c.jobId(), c.attempts(), e.toString());
      if (c.attempts() >= settings.maxAttempts()) {
        fail(tenant, c.jobId(), "IMPORT_FAILED", "The file could not be read");
      }
      return; // left running: claimed again after the stale period
    }

    // validate (pure) and give every row its natural key
    List<RowError> errors = new ArrayList<>();
    List<ImportRow.Parsed> parsed = new ArrayList<>();
    List<Integer> rowNos = new ArrayList<>();
    Map<String, Integer> occurrences = new HashMap<>();
    for (StagedRow r : staged) {
      ImportRow.Result res = ImportRow.parse(r.date(), r.amount(), r.merchant(), r.category(), r.paymentMethod(),
          r.note(), r.clientRef());
      if (!res.ok()) {
        errors.add(new RowError(r.rowNo(), res.code(), res.detail()));
        continue;
      }
      ImportRow.Parsed p = res.row();
      if (p.clientRef() == null) {
        int n = occurrences.merge(ImportRow.contentKey(p), 1, Integer::sum);
        p = new ImportRow.Parsed(p.occurredAt(), p.amountPaise(), p.merchant(), p.category(), p.paymentMethod(),
            p.note(), ImportRow.derivedRef(p, n));
      }
      parsed.add(p);
      rowNos.add(r.rowNo());
    }
    Map<String, MerchantResolver.AliasMatch> aliases = resolver.prefetch(tenant,
        parsed.stream().map(ImportRow.Parsed::merchant).filter(m -> m != null).distinct().toList());
    int total = staged.size();
    try {
      tx.writeVoid(tenant, () -> merge(tenant, c.jobId(), total, parsed, rowNos, errors, aliases));
    } catch (RuntimeException e) {
      log.warn("IMPORT_MERGE_FAILED job={} attempt={} error={}", c.jobId(), c.attempts(), e.toString());
      if (c.attempts() >= settings.maxAttempts()) {
        fail(tenant, c.jobId(), "IMPORT_FAILED", "The rows could not be saved");
      }
      return;
    }
    log.info("IMPORT_DONE job={} rows={} ms={}", c.jobId(), total, (System.nanoTime() - t0) / 1_000_000);
  }

  private void merge(Tenant tenant, UUID jobId, int total, List<ImportRow.Parsed> parsed, List<Integer> rowNos,
      List<RowError> errors, Map<String, MerchantResolver.AliasMatch> aliases) {
    Set<String> visible = new HashSet<>();
    categories.listVisible().forEach(cat -> visible.add(cat.path()));
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    MerchantResolver.Session session = resolver.session(aliases);
    List<ExpenseRow> rows = new ArrayList<>(parsed.size());
    for (int i = 0; i < parsed.size(); i++) {
      ImportRow.Parsed p = parsed.get(i);
      if (p.category() != null && !visible.contains(p.category())) {
        errors.add(new RowError(rowNos.get(i), "CATEGORY_UNKNOWN", "No such category (GET /v1/categories)"));
        continue;
      }
      ExpenseInput.Valid v = new ExpenseInput.Valid(p.occurredAt().truncatedTo(ChronoUnit.MICROS), p.amountPaise(),
          p.category(), PaymentMethod.parse(p.paymentMethod()), null, p.merchant(), p.note(), List.of(),
          p.clientRef());
      rows.add(ExpenseService.newRow(tenant.accountId(), v, session, "import", "import", now));
    }
    Set<UUID> inserted = expenses.insertAll(rows);
    List<ExpenseRow> created = rows.stream().filter(r -> inserted.contains(r.id())).toList();
    daily.apply(tenant.accountId(), DailyDelta.sum(created.stream().map(ExpenseService::counted).toList()));
    outbox.appendAll(LedgerEvents.EXPENSE_CREATED, created.stream()
        .map(r -> new OutboxWriter.Pending<>(r.accountId(), r.id(), r.version(),
            new ExpenseChangedV1(r.id(), r.version(), null, ExpenseService.state(r), now.toString())))
        .toList());
    errors.sort((a, b) -> Integer.compare(a.rowNo(), b.rowNo()));
    store.insertErrors(jobId, tenant.accountId(), errors.subList(0, Math.min(errors.size(), MAX_STORED_ERRORS)));
    int ok = rows.size();
    int duplicates = rows.size() - created.size();
    store.finish(jobId, "completed", total, ok, duplicates, errors.size(), null, null, now);
    outbox.append(LedgerEvents.IMPORT_COMPLETED, tenant.accountId(), jobId, 0,
        new ImportCompletedV1(jobId, "completed", total, ok, duplicates, errors.size(), null, now.toString()));
  }

  private void fail(Tenant tenant, UUID jobId, String code, String detail) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    tx.writeVoid(tenant, () -> {
      store.finish(jobId, "failed", 0, 0, 0, 0, code, detail, now);
      outbox.append(LedgerEvents.IMPORT_COMPLETED, tenant.accountId(), jobId, 0,
          new ImportCompletedV1(jobId, "failed", 0, 0, 0, 0, code, now.toString()));
    });
    log.info("IMPORT_FAILED job={} code={}", jobId, code);
  }
}
