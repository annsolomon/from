package in.dexter.ledger.app;

import in.dexter.contracts.events.ledger.ExpenseChangedV1;
import in.dexter.contracts.events.ledger.ExpenseStateV1;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.error.ValidationException;
import in.dexter.core.error.VersionConflictException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EventType;
import in.dexter.events.OutboxWriter;
import in.dexter.idempotency.ApiResponse;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.IdempotentRequest;
import in.dexter.idempotency.IdempotentResponse;
import in.dexter.ledger.domain.CategoryPaths;
import in.dexter.ledger.domain.DailyDelta;
import in.dexter.ledger.domain.LedgerTime;
import in.dexter.ledger.domain.PaymentMethod;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.ObjectMapper;

/**
 * Expenses (MASTER_PLAN P07, the reference implementation): every write runs in one {@code TenantTx} with its
 * daily-totals delta and its outbox event; create is Idempotency-Key protected; PATCH is versioned (If-Match);
 * delete is soft and restorable. Reads never see another tenant (RLS) or deleted rows (except restore).
 */
public final class ExpenseService {
  public static final String PATH = "/v1/expenses";
  /** Search defaults to the last 12 months and never spans more than 366 days (MASTER_PLAN P08). */
  static final int SEARCH_MAX_DAYS = 366;

  /** A write's answer: the view and whether anything changed (an event was written). */
  public record Written(ExpenseView view, boolean changed) {}

  private final TenantTx tx;
  private final IdempotencyEngine idempotency;
  private final ExpenseStore expenses;
  private final DailyTotalsStore daily;
  private final CategoryStore categories;
  private final OutboxWriter outbox;
  private final MerchantResolver resolver;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final AtomicBoolean failNext = new AtomicBoolean();

  public ExpenseService(TenantTx tx, IdempotencyEngine idempotency, ExpenseStore expenses, DailyTotalsStore daily,
      CategoryStore categories, OutboxWriter outbox, MerchantResolver resolver, ObjectMapper mapper, Clock clock) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.expenses = expenses;
    this.daily = daily;
    this.categories = categories;
    this.outbox = outbox;
    this.resolver = resolver;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** Test hook (IdempotencyContract): the next create fails after its business write. */
  public void failNextCreate() {
    failNext.set(true);
  }

  // ------------------------------------------------------------------------------------------- create

  public IdempotentResponse create(Tenant tenant, UUID key, NewExpense cmd) {
    List<FieldError> errors = new ArrayList<>();
    ExpenseInput.Valid v = ExpenseInput.validate(cmd, "", errors);
    if (v == null) {
      throw new ValidationException(errors);
    }
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH, cmd, mapper);
    Map<String, MerchantResolver.AliasMatch> aliases = resolver.prefetch(tenant,
        v.merchant() == null ? List.of() : List.of(v.merchant()));
    return tx.write(tenant, () -> idempotency.execute(tenant, key, fingerprint, () -> {
      if (v.category() != null) {
        requireCategory(v.category(), "categoryPath");
      }
      Instant now = now();
      ExpenseRow row = newRow(tenant.accountId(), v, resolver.session(aliases), "manual", "user", now);
      expenses.insert(row);
      daily.apply(tenant.accountId(), DailyDelta.between(null, counted(row)));
      event(LedgerEvents.EXPENSE_CREATED, row, null, row, now);
      if (failNext.getAndSet(false)) {
        throw new IllegalStateException("injected failure after the business write");
      }
      return new ApiResponse(201, Map.of("Location", PATH + "/" + row.id(), "ETag", etag(row.version())),
          ExpenseView.of(row));
    }));
  }

  /**
   * A new row from validated input: the merchant is resolved when one is given; a category from the caller wins
   * ({@code providedSource}, confidence 1000), otherwise the resolution (rule / merchant default / general) decides.
   */
  static ExpenseRow newRow(UUID accountId, ExpenseInput.Valid v, MerchantResolver.Session resolver, String source,
      String providedSource, Instant now) {
    MerchantResolver.Resolution r = v.merchant() == null ? null : resolver.resolve(v.merchant());
    UUID merchantId = r == null ? null : r.merchantId();
    String display = merchantId != null ? r.merchant() : v.merchant();
    String category;
    String categorySource;
    int confidence;
    if (v.category() != null) {
      category = v.category();
      categorySource = providedSource;
      confidence = 1000;
    } else if (r != null) {
      category = r.categoryPath();
      categorySource = r.categorySource();
      confidence = r.confidence();
    } else {
      category = CategoryPaths.DEFAULT;
      categorySource = "resolver";
      confidence = 0;
    }
    return new ExpenseRow(Ids.newId(), accountId, v.occurredAt(), v.amountPaise(), "INR", v.merchant(), display,
        merchantId, category, categorySource, confidence, v.method().db(), v.userCardId(), v.note(), v.tags(),
        v.clientRef(), source, now, now, 0, null);
  }

  // ------------------------------------------------------------------------------------------- reads

  public ExpenseView get(Tenant tenant, UUID id) {
    return tx.read(tenant, () -> expenses.find(id).filter(r -> !r.deleted()).map(ExpenseView::of))
        .orElseThrow(() -> new NotFoundException("expense"));
  }

  public Page<ExpenseView> list(Tenant tenant, PageRequest page, ExpenseFilter f) {
    List<FieldError> errors = new ArrayList<>();
    if (f.from() != null && f.to() != null && f.to().isBefore(f.from())) {
      errors.add(new FieldError("to", "BEFORE_FROM", "to must not be before from"));
    }
    if (f.category() != null && !CategoryPaths.isValid(f.category())) {
      errors.add(new FieldError("category", "INVALID", "Not a category path"));
    }
    if (f.paymentMethod() != null && PaymentMethod.parse(f.paymentMethod()) == null) {
      errors.add(new FieldError("paymentMethod", "INVALID", "One of card, upi, cash, netbanking, wallet, other"));
    }
    if (f.minPaise() != null && f.minPaise() < 1 || f.maxPaise() != null && f.maxPaise() < 1) {
      errors.add(new FieldError("minPaise", "INVALID", "Amounts are positive paise"));
    }
    if (f.minPaise() != null && f.maxPaise() != null && f.maxPaise() < f.minPaise()) {
      errors.add(new FieldError("maxPaise", "BELOW_MIN", "maxPaise must not be below minPaise"));
    }
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    Instant from = f.from() == null ? null : f.from().atStartOfDay(LedgerTime.ZONE).toInstant();
    Instant to = f.to() == null ? null : f.to().plusDays(1).atStartOfDay(LedgerTime.ZONE).toInstant();
    return tx.read(tenant, () -> expenses.list(page, f, from, to)).map(ExpenseView::of);
  }

  /**
   * {@code GET /v1/expenses/search?q=}: merchant (display or raw) or note containing the text, or a merchant name
   * close to it (word similarity), newest first, keyset-paged, within {@code from..to} (IST dates, inclusive; default
   * the last 12 months, at most {@value #SEARCH_MAX_DAYS} days).
   */
  public Page<ExpenseView> search(Tenant tenant, PageRequest page, String q, LocalDate from, LocalDate to) {
    String text = q == null ? "" : q.strip();
    List<FieldError> errors = new ArrayList<>();
    if (text.length() < 2 || text.length() > 60) {
      errors.add(new FieldError("q", "INVALID", "2-60 characters"));
    }
    LocalDate t = to != null ? to : LedgerTime.day(clock.instant());
    LocalDate f = from != null ? from : t.minusMonths(12).plusDays(1);
    if (t.isBefore(f) || f.plusDays(SEARCH_MAX_DAYS - 1).isBefore(t)) {
      errors.add(new FieldError("to", "INVALID_RANGE", "to >= from, at most " + SEARCH_MAX_DAYS + " days"));
    }
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    Instant fromAt = f.atStartOfDay(LedgerTime.ZONE).toInstant();
    Instant toAt = t.plusDays(1).atStartOfDay(LedgerTime.ZONE).toInstant();
    String folded = text.toLowerCase(java.util.Locale.ROOT);
    return tx.read(tenant, () -> expenses.search(page, folded, fromAt, toAt,
        Map.of("q", folded, "from", f.toString(), "to", t.toString()))).map(ExpenseView::of);
  }

  public List<DailyTotalsStore.DayTotal> dailyTotals(Tenant tenant, LocalDate from, LocalDate to) {
    if (from == null || to == null || to.isBefore(from) || from.plusDays(366).isBefore(to)) {
      throw new ValidationException(List.of(new FieldError("to", "INVALID_RANGE",
          "from and to are required, to >= from, at most 366 days")));
    }
    return tx.read(tenant, () -> daily.range(tenant.accountId(), from, to));
  }

  // ------------------------------------------------------------------------------------------- writes

  /** Merge patch with optimistic locking ({@code expectedVersion} from If-Match). */
  public ExpenseView patch(Tenant tenant, UUID id, long expectedVersion, ExpensePatch p) {
    Map<String, MerchantResolver.AliasMatch> aliases = p.merchant().present() && p.merchant().value() != null
        ? resolver.prefetch(tenant, List.of(p.merchant().value().strip())) : Map.of();
    return tx.write(tenant, () -> {
      ExpenseRow before = expenses.lock(id).filter(r -> !r.deleted())
          .orElseThrow(() -> new NotFoundException("expense"));
      if (before.version() != expectedVersion) {
        throw new VersionConflictException("expense", expectedVersion);
      }
      ExpenseRow after = apply(before, p);
      if (p.merchant().present() && !java.util.Objects.equals(before.merchantRaw(), after.merchantRaw())) {
        after = reresolve(after, !p.categoryPath().present(), resolver.session(aliases));
      }
      if (sameContent(before, after)) {
        return ExpenseView.of(before);
      }
      if (!after.categoryPath().equals(before.categoryPath())) {
        requireCategory(after.categoryPath(), "categoryPath");
      }
      Instant now = now();
      after = withMeta(after, now, before.version() + 1, before.deletedAt());
      expenses.update(after);
      daily.apply(tenant.accountId(), DailyDelta.between(counted(before), counted(after)));
      event(LedgerEvents.EXPENSE_UPDATED, after, before, after, now);
      return ExpenseView.of(after);
    });
  }

  /** Soft delete; deleting a deleted expense changes nothing (idempotent). */
  public boolean delete(Tenant tenant, UUID id) {
    return tx.write(tenant, () -> {
      ExpenseRow before = expenses.lock(id).orElseThrow(() -> new NotFoundException("expense"));
      if (before.deleted()) {
        return false;
      }
      Instant now = now();
      ExpenseRow after = withMeta(before, now, before.version() + 1, now);
      expenses.update(after);
      daily.apply(tenant.accountId(), DailyDelta.between(counted(before), null));
      event(LedgerEvents.EXPENSE_DELETED, after, before, null, now);
      return true;
    });
  }

  /** Undo a soft delete; restoring a live expense changes nothing. */
  public ExpenseView restore(Tenant tenant, UUID id) {
    return tx.write(tenant, () -> {
      ExpenseRow before = expenses.lock(id).orElseThrow(() -> new NotFoundException("expense"));
      if (!before.deleted()) {
        return ExpenseView.of(before);
      }
      Instant now = now();
      ExpenseRow after = withMeta(before, now, before.version() + 1, null);
      expenses.update(after);
      daily.apply(tenant.accountId(), DailyDelta.between(null, counted(after)));
      event(LedgerEvents.EXPENSE_RESTORED, after, null, after, now);
      return ExpenseView.of(after);
    });
  }

  // ------------------------------------------------------------------------------------------- helpers

  ExpenseRow apply(ExpenseRow r, ExpensePatch p) {
    List<FieldError> errors = new ArrayList<>();
    Instant occurred = r.occurredAt();
    if (p.occurredAt().present()) {
      if (p.occurredAt().value() == null) {
        errors.add(new FieldError("occurredAt", "REQUIRED", "occurredAt cannot be removed"));
      } else {
        occurred = p.occurredAt().value().truncatedTo(ChronoUnit.MICROS);
      }
    }
    long amount = r.amountPaise();
    if (p.amountPaise().present()) {
      amount = ExpenseInput.amount("amountPaise", p.amountPaise().value(), true, errors);
    }
    String merchant = r.merchantDisplay();
    String merchantRaw = r.merchantRaw();
    if (p.merchant().present()) {
      merchant = ExpenseInput.text("merchant", p.merchant().value(), 120, errors);
      merchantRaw = merchant;
    }
    String category = r.categoryPath();
    String categorySource = r.categorySource();
    Integer confidence = r.resolutionConfidence();
    if (p.categoryPath().present()) {
      String c = p.categoryPath().value() == null ? CategoryPaths.DEFAULT : p.categoryPath().value();
      if (!CategoryPaths.isValid(c)) {
        errors.add(new FieldError("categoryPath", "INVALID", "Not a category path"));
      } else if (!c.equals(category)) {
        category = c;
        categorySource = "user";
        confidence = 1000;
      }
    }
    String method = r.paymentMethod();
    if (p.paymentMethod().present()) {
      PaymentMethod m = PaymentMethod.parse(p.paymentMethod().value());
      if (m == null) {
        errors.add(new FieldError("paymentMethod", "INVALID", "One of card, upi, cash, netbanking, wallet, other"));
      } else {
        method = m.db();
      }
    }
    UUID card = r.userCardId();
    if (p.userCardId().present()) {
      card = p.userCardId().value();
    }
    if (!PaymentMethod.CARD.db().equals(method)) {
      if (p.userCardId().present() && card != null) {
        errors.add(new FieldError("userCardId", "NOT_A_CARD_PAYMENT", "Only card payments carry a card"));
      }
      card = null;
    }
    String note = r.note();
    if (p.note().present()) {
      note = ExpenseInput.text("note", p.note().value(), 500, errors);
    }
    List<String> tags = r.tags();
    if (p.tags().present()) {
      tags = ExpenseInput.tags("tags", p.tags().value(), errors);
    }
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    return new ExpenseRow(r.id(), r.accountId(), occurred, amount, r.currency(), merchantRaw, merchant, r.merchantId(),
        category, categorySource, confidence, method, card, note, tags, r.clientRef(), r.source(), r.createdAt(),
        r.updatedAt(), r.version(), r.deletedAt());
  }

  /** A changed merchant text: new merchant id/display, and a new category unless a person or rule chose it. */
  private static ExpenseRow reresolve(ExpenseRow r, boolean mayRecategorize, MerchantResolver.Session session) {
    MerchantResolver.Resolution res = r.merchantRaw() == null ? null : session.resolve(r.merchantRaw());
    UUID merchantId = res == null ? null : res.merchantId();
    String display = merchantId != null ? res.merchant() : r.merchantRaw();
    String category = r.categoryPath();
    String source = r.categorySource();
    Integer confidence = r.resolutionConfidence();
    if (mayRecategorize && "resolver".equals(source)) {
      category = res == null ? CategoryPaths.DEFAULT : res.categoryPath();
      source = res == null ? "resolver" : res.categorySource();
      confidence = res == null ? 0 : res.confidence();
    }
    return new ExpenseRow(r.id(), r.accountId(), r.occurredAt(), r.amountPaise(), r.currency(), r.merchantRaw(),
        display, merchantId, category, source, confidence, r.paymentMethod(), r.userCardId(), r.note(), r.tags(),
        r.clientRef(), r.source(), r.createdAt(), r.updatedAt(), r.version(), r.deletedAt());
  }

  static boolean sameContent(ExpenseRow a, ExpenseRow b) {
    return a.occurredAt().equals(b.occurredAt()) && a.amountPaise() == b.amountPaise()
        && Objects.equals(a.merchantDisplay(), b.merchantDisplay()) && Objects.equals(a.merchantRaw(), b.merchantRaw())
        && Objects.equals(a.merchantId(), b.merchantId())
        && a.categoryPath().equals(b.categoryPath()) && a.paymentMethod().equals(b.paymentMethod())
        && Objects.equals(a.userCardId(), b.userCardId()) && Objects.equals(a.note(), b.note())
        && a.tags().equals(b.tags());
  }

  static ExpenseRow withMeta(ExpenseRow r, Instant now, long version, Instant deletedAt) {
    return new ExpenseRow(r.id(), r.accountId(), r.occurredAt(), r.amountPaise(), r.currency(), r.merchantRaw(),
        r.merchantDisplay(), r.merchantId(), r.categoryPath(), r.categorySource(), r.resolutionConfidence(),
        r.paymentMethod(), r.userCardId(), r.note(), r.tags(), r.clientRef(), r.source(), r.createdAt(), now, version,
        deletedAt);
  }

  static DailyDelta.Counted counted(ExpenseRow r) {
    return r == null || r.deleted() ? null
        : new DailyDelta.Counted(LedgerTime.day(r.occurredAt()), CategoryPaths.root(r.categoryPath()), r.amountPaise());
  }

  static ExpenseStateV1 state(ExpenseRow r) {
    return r == null ? null : new ExpenseStateV1(r.amountPaise(), LedgerTime.day(r.occurredAt()).toString(),
        r.categoryPath(), r.paymentMethod(), r.userCardId(), r.merchantId(), r.occurredAt().toString());
  }

  private void event(EventType<ExpenseChangedV1> type, ExpenseRow row, ExpenseRow before, ExpenseRow after,
      Instant now) {
    outbox.append(type, row.accountId(), row.id(), row.version(),
        new ExpenseChangedV1(row.id(), row.version(), state(before), state(after), now.toString()));
  }

  void requireCategory(String path, String field) {
    if (categories.findVisible(path).isEmpty()) {
      throw new ValidationException(List.of(new FieldError(field, "UNKNOWN_CATEGORY",
          "No such category (GET /v1/categories)")));
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  static String etag(long version) {
    return "\"" + version + "\"";
  }
}
