package in.dexter.billing.app;

import in.dexter.billing.app.BillingStore.PlanRow;
import in.dexter.billing.app.BillingViews.PlanView;
import in.dexter.billing.domain.BillingErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code GET /v1/plans} and {@code GET /v1/plans/{code}} - public, like the catalog (A-55: a sentinel tenant reads
 * global rows). Not paged: there are at most three plans by the table's own CHECK (A-93).
 */
public final class PlanService {
  public static final String PATH = "/v1/plans";
  static final Tenant PUBLIC = new Tenant(new UUID(0, 0), "PUBLIC", null);

  private final TenantTx tx;
  private final BillingStore store;
  private final ObjectMapper mapper;

  public PlanService(TenantTx tx, BillingStore store, ObjectMapper mapper) {
    this.tx = tx;
    this.store = store;
    this.mapper = mapper;
  }

  public List<PlanView> list() {
    return tx.read(PUBLIC, () -> store.plans().stream().filter(PlanRow::active).map(this::view).toList());
  }

  public PlanView get(String code) {
    String c = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
    return tx.read(PUBLIC, () -> store.planByCode(c).filter(PlanRow::active).map(this::view))
        .orElseThrow(() -> new DexterException(BillingErrors.PLAN_NOT_FOUND, "No plan " + c));
  }

  @SuppressWarnings("unchecked")
  private PlanView view(PlanRow p) {
    return PlanView.of(p, mapper.readValue(p.entitlementsJson(), Map.class));
  }
}
