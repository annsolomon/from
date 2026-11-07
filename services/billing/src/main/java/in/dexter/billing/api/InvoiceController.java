package in.dexter.billing.api;

import in.dexter.billing.app.BillingViews.InvoiceView;
import in.dexter.billing.app.InvoiceService;
import in.dexter.db.page.PageRequest;
import in.dexter.security.AuthContext;
import in.dexter.web.page.PageResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /v1/invoices (MASTER_PLAN P16): the account's invoices, newest first (keyset), each with its GST lines. */
@RestController
@RequestMapping(InvoiceService.PATH)
public class InvoiceController {
  private final InvoiceService invoices;

  public InvoiceController(InvoiceService invoices) {
    this.invoices = invoices;
  }

  @GetMapping
  public PageResponse<InvoiceView> list(AuthContext caller, PageRequest page) {
    return PageResponse.from(invoices.list(Callers.user(caller), page));
  }

  @GetMapping("/{id}")
  public InvoiceView get(AuthContext caller, @PathVariable("id") UUID id) {
    return invoices.get(Callers.user(caller), id);
  }
}
