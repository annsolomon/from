package in.dexter.billing.infra;

import in.dexter.billing.app.BillingStore;
import in.dexter.billing.domain.BillingErrors;
import in.dexter.core.error.DexterException;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Plans, subscriptions, payments, webhook events and entitlements (RLS: the tenant's rows; plans are global). */
public final class BillingRepository implements BillingStore {
  static final String PLAN_COLS = "id, code, name, price_paise, billing_interval, entitlements::text AS entitlements,"
      + " active, sort_order";
  static final RowMapper<PlanRow> PLAN = (rs, n) -> new PlanRow(Rows.uuid(rs, "id"), rs.getString("code"),
      rs.getString("name"), rs.getLong("price_paise"), rs.getString("billing_interval"), rs.getString("entitlements"),
      rs.getBoolean("active"), rs.getInt("sort_order"));

  /** A subscription and the plan fields its views need: one join, never a second query per row. */
  static final String SUB_SELECT = "SELECT s.id, s.account_id, s.plan_id, p.code AS plan_code, p.name AS plan_name,"
      + " p.price_paise AS plan_price_paise, p.billing_interval, s.status, s.current_period_start,"
      + " s.current_period_end, s.cancel_at_period_end, s.canceled_at, s.ended_at, s.provider, s.provider_sub_id,"
      + " s.billing_state, s.created_at, s.updated_at, s.version"
      + " FROM billing.subscriptions s JOIN billing.plans p ON p.id = s.plan_id";
  static final RowMapper<SubscriptionRow> SUB = (rs, n) -> new SubscriptionRow(Rows.uuid(rs, "id"),
      Rows.uuid(rs, "account_id"), Rows.uuid(rs, "plan_id"), rs.getString("plan_code"), rs.getString("plan_name"),
      rs.getLong("plan_price_paise"), rs.getString("billing_interval"), rs.getString("status"),
      Rows.instant(rs, "current_period_start"), Rows.instant(rs, "current_period_end"),
      rs.getBoolean("cancel_at_period_end"), Rows.instant(rs, "canceled_at"), Rows.instant(rs, "ended_at"),
      rs.getString("provider"), rs.getString("provider_sub_id"), rs.getString("billing_state"),
      Rows.instant(rs, "created_at"), Rows.instant(rs, "updated_at"), rs.getInt("version"));

  static final String PAY_COLS = "id, account_id, subscription_id, idem_key, amount_paise, currency, status, provider,"
      + " provider_payment_id, payment_method, failure_code, created_at, updated_at, decided_at, version";
  static final RowMapper<PaymentRow> PAY = (rs, n) -> new PaymentRow(Rows.uuid(rs, "id"),
      Rows.uuid(rs, "account_id"), Rows.uuid(rs, "subscription_id"), Rows.uuid(rs, "idem_key"),
      rs.getLong("amount_paise"), rs.getString("currency"), rs.getString("status"), rs.getString("provider"),
      rs.getString("provider_payment_id"), rs.getString("payment_method"), rs.getString("failure_code"),
      Rows.instant(rs, "created_at"), Rows.instant(rs, "updated_at"), Rows.instant(rs, "decided_at"),
      rs.getInt("version"));

  static final String LIVE = " WHERE s.account_id = (SELECT billing.app_account())"
      + " AND s.status IN ('incomplete', 'trialing', 'active', 'past_due')";

  private final JdbcClient jdbc;

  public BillingRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------------------------ plans

  @Override
  public List<PlanRow> plans() {
    TenantScope.require();
    return jdbc.sql("SELECT " + PLAN_COLS + " FROM billing.plans ORDER BY sort_order, code").query(PLAN).list();
  }

  @Override
  public Optional<PlanRow> planByCode(String code) {
    TenantScope.require();
    return jdbc.sql("SELECT " + PLAN_COLS + " FROM billing.plans WHERE code = :c").param("c", code).query(PLAN)
        .optional();
  }

  @Override
  public Optional<PlanRow> plan(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + PLAN_COLS + " FROM billing.plans WHERE id = :id").param("id", id).query(PLAN)
        .optional();
  }

  // ------------------------------------------------------------------------------------------------ subscriptions

  @Override
  public Optional<SubscriptionRow> live() {
    TenantScope.require();
    return jdbc.sql(SUB_SELECT + LIVE).query(SUB).optional();
  }

  @Override
  public Optional<SubscriptionRow> latest() {
    TenantScope.require();
    return jdbc.sql(SUB_SELECT + " WHERE s.account_id = (SELECT billing.app_account())"
        + " ORDER BY s.created_at DESC, s.id DESC LIMIT 1").query(SUB).optional();
  }

  @Override
  public Optional<SubscriptionRow> subscription(UUID id) {
    TenantScope.require();
    return jdbc.sql(SUB_SELECT + " WHERE s.id = :id").param("id", id).query(SUB).optional();
  }

  @Override
  public Optional<SubscriptionRow> lockSubscription(UUID id) {
    TenantScope.require();
    return jdbc.sql(SUB_SELECT + " WHERE s.id = :id FOR UPDATE OF s").param("id", id).query(SUB).optional();
  }

  @Override
  public void insertSubscription(SubscriptionRow s) {
    TenantScope.require();
    try {
      jdbc.sql("INSERT INTO billing.subscriptions (id, account_id, plan_id, status, current_period_start,"
              + " current_period_end, cancel_at_period_end, provider, billing_state, created_at, updated_at, version)"
              + " VALUES (:id, :a, :plan, :st, CAST(:ps AS timestamptz), CAST(:pe AS timestamptz), :c, :prov, :bs,"
              + " :now, :now, :v)")
          .param("id", s.id()).param("a", s.accountId()).param("plan", s.planId()).param("st", s.status())
          .param("ps", Sql.ts(s.currentPeriodStart())).param("pe", Sql.ts(s.currentPeriodEnd()))
          .param("c", s.cancelAtPeriodEnd()).param("prov", s.provider()).param("bs", s.billingState())
          .param("now", Sql.ts(s.createdAt())).param("v", s.version()).update();
    } catch (DuplicateKeyException e) {
      if (String.valueOf(e.getMessage()).contains("subscriptions_one_live")) {
        throw new DexterException(BillingErrors.SUBSCRIPTION_EXISTS,
            "The account already has a subscription; cancel it first");
      }
      throw e;
    }
  }

  @Override
  public void updateSubscription(SubscriptionRow s) {
    TenantScope.require();
    int n = jdbc.sql("UPDATE billing.subscriptions SET status = :st, current_period_start = CAST(:ps AS timestamptz),"
            + " current_period_end = CAST(:pe AS timestamptz), cancel_at_period_end = :c,"
            + " canceled_at = CAST(:ca AS timestamptz), ended_at = CAST(:ea AS timestamptz),"
            + " provider_sub_id = CAST(:psid AS text), updated_at = :u, version = :v"
            + " WHERE id = :id AND version = :v - 1")
        .param("st", s.status()).param("ps", Sql.ts(s.currentPeriodStart())).param("pe", Sql.ts(s.currentPeriodEnd()))
        .param("c", s.cancelAtPeriodEnd()).param("ca", Sql.ts(s.canceledAt())).param("ea", Sql.ts(s.endedAt()))
        .param("psid", s.providerSubId()).param("u", Sql.ts(s.updatedAt())).param("v", s.version())
        .param("id", s.id()).update();
    if (n != 1) {
      throw new IllegalStateException("subscription " + s.id() + " was not at version " + (s.version() - 1));
    }
  }

  // ------------------------------------------------------------------------------------------------ payments

  @Override
  public void insertPayment(PaymentRow p) {
    TenantScope.require();
    jdbc.sql("INSERT INTO billing.payments (" + PAY_COLS + ") VALUES (:id, :a, :sub, :k, :amt, :cur, :st, :prov,"
            + " CAST(:ppid AS text), :pm, CAST(:fc AS text), :now, :now, CAST(:dec AS timestamptz), :v)")
        .param("id", p.id()).param("a", p.accountId()).param("sub", p.subscriptionId()).param("k", p.idemKey())
        .param("amt", p.amountPaise()).param("cur", p.currency()).param("st", p.status())
        .param("prov", p.provider()).param("ppid", p.providerPaymentId()).param("pm", p.paymentMethod())
        .param("fc", p.failureCode()).param("now", Sql.ts(p.createdAt())).param("dec", Sql.ts(p.decidedAt()))
        .param("v", p.version()).update();
  }

  @Override
  public Optional<PaymentRow> payment(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + PAY_COLS + " FROM billing.payments WHERE id = :id").param("id", id).query(PAY)
        .optional();
  }

  @Override
  public Optional<PaymentRow> lockPayment(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + PAY_COLS + " FROM billing.payments WHERE id = :id FOR UPDATE").param("id", id)
        .query(PAY).optional();
  }

  @Override
  public Optional<PaymentRow> paymentByKey(UUID idemKey) {
    TenantScope.require();
    return jdbc.sql("SELECT " + PAY_COLS + " FROM billing.payments"
            + " WHERE account_id = (SELECT billing.app_account()) AND idem_key = :k").param("k", idemKey)
        .query(PAY).optional();
  }

  @Override
  public void updatePayment(PaymentRow p) {
    TenantScope.require();
    int n = jdbc.sql("UPDATE billing.payments SET status = :st, provider_payment_id = CAST(:ppid AS text),"
            + " failure_code = CAST(:fc AS text), updated_at = :u, decided_at = CAST(:dec AS timestamptz),"
            + " version = :v WHERE id = :id AND version = :v - 1")
        .param("st", p.status()).param("ppid", p.providerPaymentId()).param("fc", p.failureCode())
        .param("u", Sql.ts(p.updatedAt())).param("dec", Sql.ts(p.decidedAt())).param("v", p.version())
        .param("id", p.id()).update();
    if (n != 1) {
      throw new IllegalStateException("payment " + p.id() + " was not at version " + (p.version() - 1));
    }
  }

  @Override
  public Optional<UUID> paymentAccount(UUID paymentId) {
    TenantScope.require();
    return jdbc.sql("SELECT account_id FROM (SELECT billing.payment_account(:p) AS account_id) x"
            + " WHERE account_id IS NOT NULL").param("p", paymentId)
        .query((rs, n) -> Rows.uuid(rs, "account_id")).optional();
  }

  // ------------------------------------------------------------------------------------------------ webhooks

  @Override
  public boolean recordWebhook(WebhookRow w) {
    TenantScope.require();
    return jdbc.sql("INSERT INTO billing.webhook_events (provider, provider_event_id, account_id, event_type,"
            + " payment_id, payload, received_at) VALUES (:prov, :ev, :a, :t, :p, CAST(:body AS jsonb), :at)"
            + " ON CONFLICT (provider, provider_event_id) DO NOTHING")
        .param("prov", w.provider()).param("ev", w.eventId()).param("a", w.accountId()).param("t", w.eventType())
        .param("p", w.paymentId()).param("body", w.payloadJson()).param("at", Sql.ts(w.receivedAt())).update() == 1;
  }

  @Override
  public void markWebhookProcessed(String provider, String eventId, String outcome, Instant at) {
    TenantScope.require();
    jdbc.sql("UPDATE billing.webhook_events SET outcome = :o, processed_at = :at"
            + " WHERE provider = :prov AND provider_event_id = :ev")
        .param("o", outcome).param("at", Sql.ts(at)).param("prov", provider).param("ev", eventId).update();
  }

  // ------------------------------------------------------------------------------------------------ entitlements

  @Override
  public Optional<EntitlementStateRow> entitlementState() {
    TenantScope.require();
    return jdbc.sql("SELECT account_id, tier, entitlements::text AS entitlements, version, updated_at"
            + " FROM billing.entitlement_state WHERE account_id = (SELECT billing.app_account())")
        .query((rs, n) -> new EntitlementStateRow(Rows.uuid(rs, "account_id"), rs.getString("tier"),
            rs.getString("entitlements"), rs.getLong("version"), Rows.instant(rs, "updated_at")))
        .optional();
  }

  @Override
  public void saveEntitlementState(EntitlementStateRow s) {
    TenantScope.require();
    // the version only grows: the caller computed it from the row it read in this transaction
    jdbc.sql("INSERT INTO billing.entitlement_state (account_id, tier, entitlements, version, updated_at)"
            + " VALUES (:a, :t, CAST(:e AS jsonb), :v, :u)"
            + " ON CONFLICT (account_id) DO UPDATE SET tier = EXCLUDED.tier, entitlements = EXCLUDED.entitlements,"
            + " version = EXCLUDED.version, updated_at = EXCLUDED.updated_at"
            + " WHERE billing.entitlement_state.version < EXCLUDED.version")
        .param("a", s.accountId()).param("t", s.tier()).param("e", s.entitlementsJson()).param("v", s.version())
        .param("u", Sql.ts(s.updatedAt())).update();
  }

  @Override
  public List<OverrideRow> overrides() {
    TenantScope.require();
    return jdbc.sql("SELECT key, value::text AS value, reason, expires_at FROM billing.entitlement_overrides"
            + " WHERE account_id = (SELECT billing.app_account()) ORDER BY key")
        .query((rs, n) -> new OverrideRow(rs.getString("key"), rs.getString("value"), rs.getString("reason"),
            Rows.instant(rs, "expires_at"))).list();
  }

  // ------------------------------------------------------------------------------------------------ jobs

  @Override
  public List<Claim> claimStaleCheckouts(long leaseMs, int limit) {
    TenantScope.require();
    return jdbc.sql("SELECT account_id, idem_key FROM billing.claim_stale_checkouts(:lease, :lim)")
        .param("lease", leaseMs).param("lim", limit)
        .query((rs, n) -> new Claim(Rows.uuid(rs, "account_id"), Rows.uuid(rs, "idem_key"))).list();
  }

  @Override
  public List<Claim> claimPeriodEnds(long graceMs, int limit) {
    TenantScope.require();
    return jdbc.sql("SELECT account_id, subscription_id FROM billing.claim_period_ends(:grace, :lim)")
        .param("grace", graceMs).param("lim", limit)
        .query((rs, n) -> new Claim(Rows.uuid(rs, "account_id"), Rows.uuid(rs, "subscription_id"))).list();
  }
}
