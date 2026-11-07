package in.dexter.billing.app;

import in.dexter.billing.app.BillingViews.InvoiceView;
import in.dexter.billing.app.InvoiceStore.InvoiceRow;
import in.dexter.billing.app.InvoiceStore.LineRow;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /v1/invoices} (keyset, newest first) and {@code GET /v1/invoices/{id}}. Every invoice comes with its
 * lines; a page's lines are one query ({@code = ANY(:ids)}), never one per invoice.
 */
public final class InvoiceService {
  public static final String PATH = "/v1/invoices";

  private final TenantTx tx;
  private final InvoiceStore invoices;

  public InvoiceService(TenantTx tx, InvoiceStore invoices) {
    this.tx = tx;
    this.invoices = invoices;
  }

  public Page<InvoiceView> list(Tenant tenant, PageRequest page) {
    return tx.read(tenant, () -> {
      Page<InvoiceRow> rows = invoices.list(page);
      Map<UUID, List<LineRow>> lines = invoices.lines(rows.items().stream().map(InvoiceRow::id).toList());
      return rows.map(i -> InvoiceView.of(i, lines.getOrDefault(i.id(), List.of())));
    });
  }

  public InvoiceView get(Tenant tenant, UUID id) {
    return tx.read(tenant, () -> {
      InvoiceRow i = invoices.find(id).orElseThrow(() -> new NotFoundException("Invoice"));
      return InvoiceView.of(i, invoices.lines(List.of(i.id())).getOrDefault(i.id(), List.of()));
    });
  }
}
