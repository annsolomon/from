package in.dexter.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Payment monotonicity, subscription periods and the period-end rules, entitlements, signatures, invoice numbers. */
class BillingDomainTest {
  static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  static final byte[] SECRET = "a-sixteen-byte-secret!".getBytes(StandardCharsets.UTF_8);

  @Test
  void aPaymentIsDecidedOnceAndNeverRegresses() {
    assertThat(PaymentStatus.PENDING.canMoveTo(PaymentStatus.SUCCEEDED)).isTrue();
    assertThat(PaymentStatus.PENDING.canMoveTo(PaymentStatus.FAILED)).isTrue();
    assertThat(PaymentStatus.PENDING.canMoveTo(PaymentStatus.PENDING)).isFalse();
    // "succeeded" before "created": the later "created" (pending) changes nothing
    assertThat(PaymentStatus.SUCCEEDED.canMoveTo(PaymentStatus.PENDING)).isFalse();
    assertThat(PaymentStatus.SUCCEEDED.canMoveTo(PaymentStatus.SUCCEEDED)).isFalse();
    assertThat(PaymentStatus.SUCCEEDED.canMoveTo(PaymentStatus.FAILED)).isFalse();
    assertThat(PaymentStatus.FAILED.canMoveTo(PaymentStatus.SUCCEEDED)).isFalse();
    assertThat(PaymentStatus.of("succeeded")).isEqualTo(PaymentStatus.SUCCEEDED);
    assertThat(PaymentStatus.FAILED.db()).isEqualTo("failed");
  }

  @Test
  void aMonthIsTheSameLocalTimeAMonthOnAndTheThirtyFirstClamps() {
    Instant start = Instant.parse("2026-01-31T04:30:00Z"); // 10:00 IST on 31 January
    assertThat(Subscriptions.periodEnd(start, "month", IST)).isEqualTo(Instant.parse("2026-02-28T04:30:00Z"));
    assertThat(Subscriptions.periodEnd(Instant.parse("2026-11-08T09:00:00Z"), "month", IST))
        .isEqualTo(Instant.parse("2026-12-08T09:00:00Z"));
    assertThat(Subscriptions.periodEnd(Instant.parse("2028-02-29T09:00:00Z"), "year", IST))
        .isEqualTo(Instant.parse("2029-02-28T09:00:00Z"));
    assertThatThrownBy(() -> Subscriptions.periodEnd(start, "week", IST)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void periodEndCancelsGoesPastDueAndExpiresAfterTheGrace() {
    Instant end = Instant.parse("2026-12-08T09:00:00Z");
    Duration grace = Duration.ofDays(3);
    assertThat(Subscriptions.atPeriodEnd("active", false, end, end.minusSeconds(1), grace)).isEmpty();
    assertThat(Subscriptions.atPeriodEnd("active", true, end, end, grace)).contains("canceled");
    assertThat(Subscriptions.atPeriodEnd("active", false, end, end, grace)).contains("past_due");
    assertThat(Subscriptions.atPeriodEnd("trialing", false, end, end, grace)).contains("past_due");
    assertThat(Subscriptions.atPeriodEnd("past_due", false, end, end.plus(Duration.ofDays(2)), grace)).isEmpty();
    assertThat(Subscriptions.atPeriodEnd("past_due", false, end, end.plus(grace), grace)).contains("expired");
    assertThat(Subscriptions.atPeriodEnd("past_due", true, end, end.plusSeconds(5), grace)).contains("canceled");
    assertThat(Subscriptions.atPeriodEnd("canceled", false, end, end.plus(grace), grace)).isEmpty();
    assertThat(Subscriptions.atPeriodEnd("incomplete", false, null, end, grace)).isEmpty();
    assertThat(Subscriptions.LIVE).contains("incomplete").doesNotContain("incomplete_expired", "canceled");
    assertThat(Subscriptions.ENTITLED).doesNotContain("incomplete");
  }

  @Test
  void theTierFollowsAnEntitlingSubscriptionAndOverridesLayOverThePlan() {
    assertThat(Entitlements.tier(null, null)).isEqualTo("FREE");
    assertThat(Entitlements.tier("incomplete", "PREMIUM")).isEqualTo("FREE");
    assertThat(Entitlements.tier("active", "PREMIUM")).isEqualTo("PREMIUM");
    assertThat(Entitlements.tier("past_due", "VIP")).isEqualTo("VIP");
    assertThat(Entitlements.tier("canceled", "VIP")).isEqualTo("FREE");
    Instant now = Instant.parse("2026-11-08T00:00:00Z");
    Map<String, Object> plan = Map.of("adFree", false, "cardsMax", 3);
    Entitlements.Effective e = Entitlements.effective("FREE", plan, List.of(
        new Entitlements.Override("adFree", true, now.plusSeconds(60)),
        new Entitlements.Override("cardsMax", 99, now.minusSeconds(1)),
        new Entitlements.Override("bonus", "x", now.plusSeconds(10))), now);
    assertThat(e.entitlements()).containsEntry("adFree", true).containsEntry("cardsMax", 3)
        .containsEntry("bonus", "x");
    assertThat(e.entitlements().keySet()).containsExactly("adFree", "bonus", "cardsMax");
    assertThat(Entitlements.active(List.of(new Entitlements.Override("a", 1, now.plusSeconds(60)),
        new Entitlements.Override("b", 1, now.plusSeconds(5)), new Entitlements.Override("c", 1, now)), now))
        .extracting(Entitlements.Override::key).containsExactly("b", "a");
  }

  @Test
  void aSignatureVerifiesOnlyForTheSameBytesSecretAndWindow() {
    byte[] body = "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
    Instant now = Instant.parse("2026-11-08T10:00:00Z");
    String h = WebhookSignature.header(SECRET, now.getEpochSecond(), body);
    Duration tol = WebhookSignature.TOLERANCE;
    assertThat(h).matches("t=\\d+,v1=[0-9a-f]{64}");
    assertThat(WebhookSignature.verify(SECRET, h, body, now, tol)).isEqualTo(WebhookSignature.Check.OK);
    assertThat(WebhookSignature.verify(SECRET, h, body, now.plus(Duration.ofMinutes(5)), tol))
        .isEqualTo(WebhookSignature.Check.OK);
    assertThat(WebhookSignature.verify(SECRET, h, body, now.plus(Duration.ofMinutes(6)), tol))
        .isEqualTo(WebhookSignature.Check.STALE);
    assertThat(WebhookSignature.verify(SECRET, h, body, now.minus(Duration.ofMinutes(6)), tol))
        .isEqualTo(WebhookSignature.Check.STALE);
    byte[] tampered = "{\"id\":\"evt_2\"}".getBytes(StandardCharsets.UTF_8);
    assertThat(WebhookSignature.verify(SECRET, h, tampered, now, tol)).isEqualTo(WebhookSignature.Check.MISMATCH);
    assertThat(WebhookSignature.verify("another-sixteen-byte".getBytes(StandardCharsets.UTF_8), h, body, now, tol))
        .isEqualTo(WebhookSignature.Check.MISMATCH);
    assertThat(WebhookSignature.verify(SECRET, null, body, now, tol)).isEqualTo(WebhookSignature.Check.MISSING);
    assertThat(WebhookSignature.verify(SECRET, "t=abc,v1=00", body, now, tol))
        .isEqualTo(WebhookSignature.Check.MALFORMED);
    assertThat(WebhookSignature.verify(SECRET, "v1=" + "0".repeat(64), body, now, tol))
        .isEqualTo(WebhookSignature.Check.MALFORMED);
    assertThatThrownBy(() -> WebhookSignature.header("short".getBytes(StandardCharsets.UTF_8), 1, body))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void invoiceNumbersCarryTheIndianFinancialYear() {
    assertThat(InvoiceNumbers.format(Instant.parse("2026-11-08T09:00:00Z"), 42)).isEqualTo("DX/2026-27/000042");
    // 31 March 23:30 IST is still the old year; 1 April 00:10 IST is the new one
    assertThat(InvoiceNumbers.financialYear(Instant.parse("2027-03-31T18:00:00Z"))).isEqualTo("2026-27");
    assertThat(InvoiceNumbers.financialYear(Instant.parse("2027-03-31T18:40:00Z"))).isEqualTo("2027-28");
    assertThat(InvoiceNumbers.financialYear(Instant.parse("2099-06-01T00:00:00Z"))).isEqualTo("2099-00");
    assertThat(InvoiceNumbers.format(Instant.parse("2026-11-08T09:00:00Z"), 1_234_567)).isEqualTo("DX/2026-27/1234567");
    assertThatThrownBy(() -> InvoiceNumbers.format(Instant.now(), 0)).isInstanceOf(IllegalArgumentException.class);
  }
}
