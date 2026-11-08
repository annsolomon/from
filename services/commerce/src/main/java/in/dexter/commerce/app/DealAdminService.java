package in.dexter.commerce.app;

import in.dexter.commerce.app.CommerceViews.AdminDealView;
import in.dexter.commerce.app.CommerceViews.Component;
import in.dexter.commerce.app.DealStore.Deal;
import in.dexter.commerce.domain.CommerceErrors;
import in.dexter.commerce.domain.SavingComponent;
import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * {@code /v1/admin/commerce/deals} (operators, on the admin pool): create, list, read and move a deal's status
 * (draft → active ⇄ paused → ended; ended is final). Deals are global marketplace data; nobody else writes them.
 */
public final class DealAdminService {
  public static final String PATH = "/v1/admin/commerce/deals";
  static final Pattern NETWORK = Pattern.compile("[a-z][a-z0-9-]{1,30}");
  static final Pattern HTTPS = Pattern.compile("https://\\S{4,}");
  private static final Map<String, Set<String>> MOVES = Map.of(
      "draft", Set.of("active", "ended"),
      "active", Set.of("paused", "ended"),
      "paused", Set.of("active", "ended"),
      "ended", Set.of());

  public record NewDeal(@Nullable UUID merchantId, @Nullable String merchantName, @Nullable String title,
      @Nullable String description, @Nullable String categoryPath, @Nullable List<Component> components,
      @Nullable List<UUID> eligibleCardProducts, @Nullable Long referenceSpendPaise, @Nullable Instant validFrom,
      @Nullable Instant validTo, @Nullable String status, @Nullable Integer priority, @Nullable Boolean sponsored,
      @Nullable Integer sponsorWeight, @Nullable String network, @Nullable String landingUrl,
      @Nullable Integer commissionBps) {}

  public record StatusChange(@Nullable String status) {}

  private final TenantTx tx;
  private final DealStore deals;
  private final Clock clock;

  public DealAdminService(TenantTx tx, DealStore deals, Clock clock) {
    this.tx = tx;
    this.deals = deals;
    this.clock = clock;
  }

  public AdminDealView create(Tenant operator, NewDeal in) {
    List<FieldError> errors = new ArrayList<>();
    require(errors, in.merchantId() != null, "merchantId", "required");
    String merchant = text(errors, "merchantName", in.merchantName(), 80);
    String title = text(errors, "title", in.title(), 120);
    String description = in.description() == null ? "" : in.description().strip();
    require(errors, description.length() <= 1000, "description", "at most 1000 characters");
    String category = null;
    try {
      category = DealService.category(in.categoryPath());
    } catch (ValidationException e) {
      errors.addAll(e.fieldErrors());
    }
    require(errors, category != null, "categoryPath", "required, e.g. shopping.online");
    List<SavingComponent> components = List.of();
    try {
      components = Component.toDomain(in.components());
    } catch (ValidationException e) {
      errors.addAll(e.fieldErrors());
    }
    List<UUID> eligible = in.eligibleCardProducts() == null ? List.of()
        : List.copyOf(new LinkedHashSet<>(in.eligibleCardProducts()));
    require(errors, eligible.size() <= 50, "eligibleCardProducts", "at most 50");
    long reference = in.referenceSpendPaise() == null ? 0 : in.referenceSpendPaise();
    require(errors, reference >= 1 && reference <= SavingComponent.MAX_PAISE, "referenceSpendPaise",
        "1.." + SavingComponent.MAX_PAISE);
    Instant now = clock.instant();
    Instant from = in.validFrom() == null ? now : in.validFrom();
    require(errors, in.validTo() == null || in.validTo().isAfter(from), "validTo", "after validFrom");
    String status = in.status() == null ? "active" : in.status();
    require(errors, status.equals("draft") || status.equals("active"), "status", "draft or active");
    int priority = in.priority() == null ? 0 : in.priority();
    require(errors, priority >= -100 && priority <= 100, "priority", "-100..100");
    boolean sponsored = Boolean.TRUE.equals(in.sponsored());
    int weight = in.sponsorWeight() == null ? 0 : in.sponsorWeight();
    require(errors, weight >= 0 && weight <= 100 && (sponsored || weight == 0), "sponsorWeight",
        "0..100, and 0 unless sponsored");
    require(errors, in.network() != null && NETWORK.matcher(in.network()).matches(), "network",
        "a network code such as mock");
    require(errors, in.landingUrl() != null && in.landingUrl().length() <= 2000
        && HTTPS.matcher(in.landingUrl()).matches(), "landingUrl", "an https URL");
    int commission = in.commissionBps() == null ? 0 : in.commissionBps();
    require(errors, commission >= 0 && commission <= 10_000, "commissionBps", "0..10000");
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    Deal d = new Deal(Ids.newId(), in.merchantId(), merchant, title, description, category, components, eligible,
        reference, from, in.validTo(), status, priority, sponsored, weight, in.network(), in.landingUrl(), commission,
        0, now);
    return tx.write(operator, () -> {
      deals.insert(d, operator.accountId());
      return AdminDealView.of(deals.findDeal(d.id()).orElseThrow());
    });
  }

  public Page<AdminDealView> list(Tenant operator, PageRequest page) {
    return tx.read(operator, () -> deals.adminList(page).map(AdminDealView::of));
  }

  public AdminDealView get(Tenant operator, UUID id) {
    return tx.read(operator, () -> AdminDealView.of(deals.findDeal(id)
        .orElseThrow(() -> new DexterException(CommerceErrors.DEAL_NOT_FOUND, "No deal with that id"))));
  }

  public AdminDealView setStatus(Tenant operator, UUID id, long expectedVersion, StatusChange in) {
    String next = in.status();
    if (next == null || !MOVES.containsKey(next)) {
      throw ValidationException.of("status", "STATUS_INVALID", "draft, active, paused or ended");
    }
    return tx.write(operator, () -> {
      Deal d = deals.findDeal(id)
          .orElseThrow(() -> new DexterException(CommerceErrors.DEAL_NOT_FOUND, "No deal with that id"));
      if (d.version() != expectedVersion) {
        throw new DexterException(CoreErrors.VERSION_CONFLICT, "The deal changed; reload it");
      }
      if (!d.status().equals(next)) {
        if (!MOVES.get(d.status()).contains(next)) {
          throw ValidationException.of("status", "STATUS_TRANSITION_INVALID", d.status() + " cannot move to " + next);
        }
        if (!deals.updateStatus(id, next, d.version(), clock.instant())) {
          throw new DexterException(CoreErrors.VERSION_CONFLICT, "The deal changed; reload it");
        }
      }
      return AdminDealView.of(deals.findDeal(id).orElseThrow());
    });
  }

  static void require(List<FieldError> errors, boolean ok, String field, String message) {
    if (!ok) {
      errors.add(new FieldError(field, "INVALID", message));
    }
  }

  static String text(List<FieldError> errors, String field, @Nullable String value, int max) {
    String v = value == null ? "" : value.strip();
    if (v.isEmpty() || v.length() > max) {
      errors.add(new FieldError(field, "INVALID", "1.." + max + " characters"));
    }
    return v;
  }
}
