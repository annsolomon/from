package in.dexter.audit.domain;

import java.util.Locale;
import java.util.Map;

/**
 * The notification texts (MASTER_PLAN P18): a title and a body per template, filled from the payload. Unknown
 * payload fields are ignored, missing ones render as empty. Money is in paise and shown in rupees.
 */
public final class Templates {
  /** A rendered notification. */
  public record Text(String title, String body) {}

  private Templates() {}

  public static Text render(String template, Map<String, Object> p) {
    return switch (template) {
      case "security.refresh_token_reuse" -> new Text("Suspicious sign-in activity",
          "A signed-out session tried to refresh. We revoked that sign-in; change your password if it wasn't you.");
      case "security.account_locked" -> new Text("Account locked",
          "Too many failed sign-ins. Your account is locked for a while.");
      case "security.mfa_enabled" -> new Text("Two-factor authentication on",
          "Two-factor authentication was turned on for your account.");
      case "security.mfa_disabled" -> new Text("Two-factor authentication off",
          "Two-factor authentication was turned off for your account. If this wasn't you, secure your account.");
      case "security.recovery_codes_regenerated" -> new Text("New recovery codes",
          "Your recovery codes were regenerated; the old ones no longer work.");
      case "security.recovery_code_used" -> new Text("Recovery code used",
          "A recovery code was used to sign in to your account.");
      case "budget.threshold" -> new Text("Budget " + s(p, "name") + ": " + s(p, "threshold") + "% used",
          "You have spent " + rupees(p, "spentPaise") + " of " + rupees(p, "limitPaise") + " this " + s(p, "period")
              + " period.");
      case "billing.subscription" -> new Text("Subscription " + s(p, "status"),
          "Your " + s(p, "planCode") + " subscription is " + s(p, "status") + ".");
      case "commerce.application" -> new Text("Card application " + s(p, "status").replace('_', ' '),
          "Your card application is now " + s(p, "status").replace('_', ' ') + "."
              + (s(p, "reason").isEmpty() ? "" : " " + s(p, "reason")));
      case "privacy.export-ready" -> new Text("Your data export is ready",
          "Download it within 24 hours from Privacy settings.");
      default -> new Text(template, "");
    };
  }

  static String s(Map<String, Object> p, String key) {
    Object v = p.get(key);
    return v == null ? "" : v.toString();
  }

  static String rupees(Map<String, Object> p, String key) {
    Object v = p.get(key);
    if (!(v instanceof Number n)) {
      return "";
    }
    long paise = n.longValue();
    return String.format(Locale.ROOT, "₹%,d.%02d", paise / 100, Math.abs(paise % 100));
  }
}
