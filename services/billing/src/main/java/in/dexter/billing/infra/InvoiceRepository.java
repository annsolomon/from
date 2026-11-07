package in.dexter.billing.infra;

import in.dexter.billing.app.InvoiceStore;
import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Invoices and their lines (RLS: the tenant's rows). */
public final class InvoiceRepository implements InvoiceStore {
  static final String COLS = "id, account_id, subscription_id, payment_id, number, issued_at, period_start, period_end,"
      + " supplier_state, place_of_supply, tax_regime, subtotal_paise, tax_paise, total_paise, currency, status";
  static final RowMapper<InvoiceRow> INVOICE = (rs, n) -> new InvoiceRow(Rows.uuid(rs, "id"),
      Rows.uuid(rs, "account_id"), Rows.uuid(rs, "subscription_id"), Rows.uuid(rs, "payment_id"),
      rs.getString("number"), Rows.instant(rs, "issued_at"), Rows.instant(rs, "period_start"),
      Rows.instant(rs, "period_end"), rs.getString("supplier_state"), rs.getString("place_of_supply"),
      rs.getString("tax_regime"), rs.getLong("subtotal_paise"), rs.getLong("tax_paise"), rs.getLong("total_paise"),
      rs.getString("currency"), rs.getString("status"));
  static final RowMapper<LineRow> LINE = (rs, n) -> new LineRow(Rows.uuid(rs, "invoice_id"), rs.getInt("line_no"),
      rs.getString("kind"), rs.getString("description"), Rows.nullableInt(rs, "rate_bps"), rs.getLong("amount_paise"));

  /** Newest first on invoices_list (account_id, issued_at DESC, id DESC). Public: the pagination IT plan-asserts it. */
  public static final KeysetQuery<InvoiceRow> LIST = KeysetQuery.<InvoiceRow>builder("issued_desc")
      .direction(KeysetQuery.Direction.DESC)
      .key("issued_at", KeyType.TIMESTAMPTZ)
      .key("id", KeyType.UUID)
      .sql("SELECT " + COLS + " FROM billing.invoices WHERE account_id = (SELECT billing.app_account())")
      .mapper(INVOICE)
      .keysOf(i -> List.of(i.issuedAt(), i.id()))
      .build();

  private final JdbcClient jdbc;
  private final KeysetPager pager;

  public InvoiceRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
  }

  @Override
  public long nextSerial() {
    TenantScope.require();
    return jdbc.sql("SELECT nextval('billing.invoice_number_seq')").query(Long.class).single();
  }

  @Override
  public void insert(InvoiceRow i, List<LineRow> lines) {
    TenantScope.require();
    jdbc.sql("INSERT INTO billing.invoices (" + COLS + ") VALUES (:id, :a, :sub, :pay, :num, :at, :ps, :pe, :ss,"
            + " :pos, :reg, :subtotal, :tax, :total, :cur, :st)")
        .param("id", i.id()).param("a", i.accountId()).param("sub", i.subscriptionId()).param("pay", i.paymentId())
        .param("num", i.number()).param("at", Sql.ts(i.issuedAt())).param("ps", Sql.ts(i.periodStart()))
        .param("pe", Sql.ts(i.periodEnd())).param("ss", i.supplierState()).param("pos", i.placeOfSupply())
        .param("reg", i.taxRegime()).param("subtotal", i.subtotalPaise()).param("tax", i.taxPaise())
        .param("total", i.totalPaise()).param("cur", i.currency()).param("st", i.status()).update();
    for (LineRow l : lines) {
      jdbc.sql("INSERT INTO billing.invoice_lines (invoice_id, account_id, line_no, kind, description, rate_bps,"
              + " amount_paise) VALUES (:inv, :a, :no, :kind, :d, CAST(:r AS integer), :amt)")
          .param("inv", l.invoiceId()).param("a", i.accountId()).param("no", l.lineNo()).param("kind", l.kind())
          .param("d", l.description()).param("r", l.rateBps()).param("amt", l.amountPaise()).update();
    }
  }

  @Override
  public Optional<InvoiceRow> find(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM billing.invoices WHERE id = :id").param("id", id).query(INVOICE)
        .optional();
  }

  @Override
  public Optional<InvoiceRow> byPayment(UUID paymentId) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM billing.invoices WHERE payment_id = :p").param("p", paymentId)
        .query(INVOICE).optional();
  }

  @Override
  public Page<InvoiceRow> list(PageRequest page) {
    TenantScope.require();
    return pager.page(jdbc, LIST, page, Map.of(), Map.of());
  }

  @Override
  public Map<UUID, List<LineRow>> lines(Collection<UUID> invoiceIds) {
    TenantScope.require();
    Map<UUID, List<LineRow>> out = new LinkedHashMap<>();
    if (invoiceIds.isEmpty()) {
      return out;
    }
    for (LineRow l : jdbc.sql("SELECT invoice_id, line_no, kind, description, rate_bps, amount_paise"
            + " FROM billing.invoice_lines WHERE invoice_id = ANY(:ids) ORDER BY invoice_id, line_no")
        .param("ids", Sql.uuids(invoiceIds)).query(LINE).list()) {
      out.computeIfAbsent(l.invoiceId(), k -> new ArrayList<>()).add(l);
    }
    return out;
  }
}
