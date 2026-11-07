package in.dexter.billing;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.billing.support.BillingIT;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestPostgres;
import in.dexter.testkit.rls.RlsProofKit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * RLS proof (MASTER_PLAN 10) on every billing tenant table: ENABLE + FORCE + a policy, isolation, fail-closed, WITH
 * CHECK, and that billing_app cannot escape. Plans are global (read by every tenant, written by none) and
 * entitlement_overrides has no app writer yet, so those two get their own checks.
 */
class BillingRlsIT extends BillingIT {
  static final ServiceDb DBX = TestPostgres.flywayDb("billing");
  static final String PLAN = "'0192f1e2-0000-7000-8000-00000000b002'";

  /** A closed subscription of the tenant parameter (closed, so two of them never meet subscriptions_one_live). */
  static final String SUB = "WITH s AS (INSERT INTO billing.subscriptions (id, account_id, plan_id, status,"
      + " current_period_start, current_period_end, provider, billing_state) VALUES (gen_random_uuid(), ?, " + PLAN
      + ", 'canceled', now() - interval '1 month', now(), 'mock', 'KA') RETURNING id, account_id)";
  static final String PAY = SUB + ", p AS (INSERT INTO billing.payments (id, account_id, subscription_id, idem_key,"
      + " amount_paise, status, provider, payment_method, decided_at) SELECT gen_random_uuid(), account_id, id,"
      + " gen_random_uuid(), 23482, 'succeeded', 'mock', 'pm_mock_success', now() FROM s"
      + " RETURNING id, account_id, subscription_id)";
  static final String INV = PAY + ", i AS (INSERT INTO billing.invoices (id, account_id, subscription_id, payment_id,"
      + " number, issued_at, period_start, period_end, supplier_state, place_of_supply, tax_regime, subtotal_paise,"
      + " tax_paise, total_paise) SELECT gen_random_uuid(), account_id, subscription_id, id,"
      + " 'DX/2026-27/' || lpad(nextval('billing.invoice_number_seq')::text, 6, '0'), now(), now(),"
      + " now() + interval '1 month', 'KA', 'KA', 'intra', 19900, 3582, 23482 FROM p RETURNING id, account_id)";

  @Autowired
  TenantTx tx;
  @Autowired
  JdbcClient jdbc;

  static RlsProofKit kit(String table, String insert, String move) {
    return new RlsProofKit(DBX, table, insert, move);
  }

  @Test
  void subscriptions() throws Exception {
    kit("subscriptions", SUB + " SELECT 1", "UPDATE billing.subscriptions SET account_id = ?").proveAll();
  }

  @Test
  void payments() throws Exception {
    kit("payments", PAY + " SELECT 1", "UPDATE billing.payments SET account_id = ?").proveAll();
  }

  @Test
  void invoices() throws Exception {
    kit("invoices", INV + " SELECT 1", null).proveAll(); // written once: the app role has no UPDATE
  }

  @Test
  void invoiceLines() throws Exception {
    kit("invoice_lines", INV + " INSERT INTO billing.invoice_lines (invoice_id, account_id, line_no, kind,"
        + " description, amount_paise) SELECT id, account_id, 1, 'item', 'x', 19900 FROM i", null).proveAll();
  }

  @Test
  void webhookEvents() throws Exception {
    kit("webhook_events", "INSERT INTO billing.webhook_events (provider, provider_event_id, account_id, event_type,"
        + " payment_id, payload) VALUES ('mock', 'evt_' || gen_random_uuid(), ?, 'payment.succeeded',"
        + " gen_random_uuid(), '{}'::jsonb)", "UPDATE billing.webhook_events SET account_id = ?").proveAll();
  }

  @Test
  void entitlementState() throws Exception {
    kit("entitlement_state", "INSERT INTO billing.entitlement_state (account_id, tier, entitlements, version)"
        + " VALUES (?, 'PREMIUM', '{}'::jsonb, 1)", "UPDATE billing.entitlement_state SET account_id = ?").proveAll();
  }

  @Test
  void entitlementOverridesAreReadOnlyAndIsolated() throws Exception {
    RlsProofKit k = kit("entitlement_overrides", "unused", null);
    k.flagsAndPolicies();
    k.appCannotEscape();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    ADMIN.sql("INSERT INTO billing.entitlement_overrides (account_id, key, value, reason, expires_at)"
        + " VALUES (:a, 'adFree', 'true'::jsonb, 'rls', now() + interval '1 day')").param("a", a).update();
    long own = tx.read(Tenant.user(a), () -> jdbc.sql("SELECT count(1) FROM billing.entitlement_overrides")
        .query(Long.class).single());
    long foreign = tx.read(Tenant.user(b), () -> jdbc.sql("SELECT count(1) FROM billing.entitlement_overrides")
        .query(Long.class).single());
    assertThat(own).isEqualTo(1);
    assertThat(foreign).isZero();
  }

  @Test
  void plansAreReadByEveryoneAndWrittenByNoOne() throws Exception {
    RlsProofKit k = kit("plans", "unused", null);
    k.flagsAndPolicies();
    k.appCannotEscape();
    long seen = tx.read(Tenant.user(UUID.randomUUID()), () -> jdbc.sql("SELECT count(1) FROM billing.plans")
        .query(Long.class).single());
    assertThat(seen).isEqualTo(3);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> DBX.exec("app",
        "UPDATE billing.plans SET price_paise = 1 WHERE code = 'PREMIUM'")).isInstanceOf(IllegalStateException.class);
  }
}
