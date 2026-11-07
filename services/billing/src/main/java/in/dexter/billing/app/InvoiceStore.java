package in.dexter.billing.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Invoices and their lines (RLS per account). Written once, never updated. */
public interface InvoiceStore {

  record InvoiceRow(UUID id, UUID accountId, UUID subscriptionId, UUID paymentId, String number, Instant issuedAt,
      Instant periodStart, Instant periodEnd, String supplierState, String placeOfSupply, String taxRegime,
      long subtotalPaise, long taxPaise, long totalPaise, String currency, String status) {}

  record LineRow(UUID invoiceId, int lineNo, String kind, String description, Integer rateBps, long amountPaise) {}

  /** The next serial of the invoice-number sequence. */
  long nextSerial();

  void insert(InvoiceRow invoice, List<LineRow> lines);

  Optional<InvoiceRow> find(UUID id);

  Optional<InvoiceRow> byPayment(UUID paymentId);

  Page<InvoiceRow> list(PageRequest page);

  /** The lines of many invoices in one query, by invoice id, in line order. */
  Map<UUID, List<LineRow>> lines(Collection<UUID> invoiceIds);
}
