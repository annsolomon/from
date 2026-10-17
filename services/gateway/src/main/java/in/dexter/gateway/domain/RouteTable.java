package in.dexter.gateway.domain;

import java.util.List;

/**
 * The gateway's whole route table (MASTER_PLAN P06 step 1). External paths equal service paths ({@code /v1/...});
 * the trail's nginx strips its {@code /api} prefix. Order matters only where prefixes overlap: the SSE route before
 * the rest of reco, affiliate webhooks (commerce) before the other webhooks (billing). Every pattern here is listed
 * in {@code docs/api/routes.md}; {@code RouteTableIT} sends each documented example path through the gateway to
 * per-service WireMock backends.
 */
public final class RouteTable {
  public static final List<ServiceRoute> ROUTES = List.of(
      new ServiceRoute("reco-stream", "reco", List.of("/v1/recommendations/stream"), true),
      new ServiceRoute("commerce-affiliate-webhooks", "commerce", List.of("/v1/webhooks/affiliate/**"), false),
      ServiceRoute.of("identity",
          "/v1/auth/**", "/v1/me", "/v1/me/**",
          "/v1/admin/auth/**", "/v1/admin/me", "/v1/admin/admins", "/v1/admin/admins/**",
          "/v1/admin/accounts", "/v1/admin/accounts/**", "/v1/admin/elevations", "/v1/admin/elevations/**",
          "/.well-known/jwks.json"),
      ServiceRoute.of("ledger",
          "/v1/expenses", "/v1/expenses/**", "/v1/expenses:*", "/v1/categories", "/v1/categories/**",
          "/v1/merchants/**", "/v1/rules", "/v1/rules/**", "/v1/imports", "/v1/imports/**", "/v1/summary",
          "/v1/calendar"),
      ServiceRoute.of("catalog",
          "/v1/catalog/**", "/v1/admin/catalog/**", "/v1/admin/ingestion/**", "/v1/admin/review",
          "/v1/admin/review/**"),
      ServiceRoute.of("portfolio",
          "/v1/cards", "/v1/cards/**", "/v1/emi-plans", "/v1/emi-plans/**", "/v1/links/**", "/v1/portfolio/**",
          "/v1/rewards/**", "/v1/statements/**", "/v1/calc/**"),
      ServiceRoute.of("planning",
          "/v1/budgets", "/v1/budgets/**", "/v1/liabilities", "/v1/liabilities/**", "/v1/scenarios",
          "/v1/scenarios/**"),
      ServiceRoute.of("reco", "/v1/recommendations", "/v1/recommendations/**"),
      ServiceRoute.of("billing",
          "/v1/plans", "/v1/plans/**", "/v1/subscriptions/**", "/v1/entitlements/**", "/v1/invoices",
          "/v1/invoices/**", "/v1/webhooks/**"),
      ServiceRoute.of("commerce",
          "/v1/deals", "/v1/deals/**", "/v1/card-applications", "/v1/card-applications/**", "/v1/ads/**"),
      ServiceRoute.of("audit",
          "/v1/notifications", "/v1/notifications/**", "/v1/notification-prefs", "/v1/notification-prefs/**",
          "/v1/privacy/**", "/v1/admin/audit", "/v1/admin/audit/**", "/v1/admin/audit:*",
          "/v1/admin/activity/**"));

  /**
   * Reachable without a token (early rejection is skipped; the service applies its own rules). Sign-in flows,
   * JWKS, public catalog / plans / deals / ads reads, provider webhooks (HMAC-verified by the service) and the SSE
   * stream (one-time ticket in the query, EventSource cannot send headers).
   */
  public static final List<PublicRoute> PUBLIC = List.of(
      new PublicRoute("POST", "/v1/auth/register"),
      new PublicRoute("POST", "/v1/auth/verify-email"),
      new PublicRoute("POST", "/v1/auth/login"),
      new PublicRoute("POST", "/v1/auth/token/refresh"),
      new PublicRoute("POST", "/v1/auth/password/forgot"),
      new PublicRoute("POST", "/v1/auth/password/reset"),
      new PublicRoute("POST", "/v1/auth/mfa/verify"),
      new PublicRoute("POST", "/v1/admin/auth/login"),
      new PublicRoute("POST", "/v1/admin/auth/mfa/setup"),
      new PublicRoute("POST", "/v1/admin/auth/mfa/verify"),
      new PublicRoute("POST", "/v1/admin/auth/token/refresh"),
      new PublicRoute("POST", "/v1/admin/auth/invite/accept"),
      new PublicRoute("GET", "/.well-known/jwks.json"),
      new PublicRoute("GET", "/v1/catalog/**"),
      new PublicRoute("GET", "/v1/plans"),
      new PublicRoute("GET", "/v1/plans/**"),
      new PublicRoute("GET", "/v1/deals"),
      new PublicRoute("GET", "/v1/deals/**"),
      new PublicRoute("GET", "/v1/ads/serve"),
      new PublicRoute("POST", "/v1/webhooks/**"),
      new PublicRoute("GET", "/v1/recommendations/stream"));

  /** Dev profile only: the trail's dev endpoints of identity (P04 key rotation). */
  public static final ServiceRoute DEV_IDENTITY = new ServiceRoute("identity-dev", "identity",
      List.of("/_dev/p04/**"), false);

  public static final List<String> SERVICES = List.of("identity", "ledger", "catalog", "portfolio", "planning",
      "reco", "billing", "commerce", "audit");

  private RouteTable() {}
}
