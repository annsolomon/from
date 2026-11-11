package in.dexter.audit.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Who hears what (MASTER_PLAN P18). Every notification goes to the in-app inbox; email and push follow the
 * account's switches; a muted category gets nothing at all — except security, which cannot be muted and always
 * goes in-app and by email.
 */
public final class NotificationRules {
  public static final String SECURITY = "security";
  public static final String BUDGET = "budget";
  public static final String BILLING = "billing";
  public static final String COMMERCE = "commerce";
  public static final String PRIVACY = "privacy";
  public static final Set<String> CATEGORIES = Set.of(SECURITY, BUDGET, BILLING, COMMERCE, PRIVACY);
  public static final Set<String> MUTABLE = Set.of(BUDGET, BILLING, COMMERCE, PRIVACY);
  public static final String INAPP = "inapp";
  public static final String EMAIL = "email";
  public static final String PUSH = "push";

  /** An account's preferences (the defaults when it has none). */
  public record Prefs(boolean email, boolean push, Set<String> muted, int version) {
    public static final Prefs DEFAULTS = new Prefs(true, true, Set.of(), 0);

    public Prefs {
      muted = Set.copyOf(muted);
    }
  }

  private NotificationRules() {}

  /**
   * The channels a notification of {@code category} goes to. {@code wantsPush} is the template's own choice
   * (commerce decisions push; budget alerts do not).
   */
  public static List<String> channels(String category, boolean wantsPush, Prefs prefs) {
    if (!CATEGORIES.contains(category)) {
      throw new IllegalArgumentException("unknown category: " + category);
    }
    List<String> out = new ArrayList<>();
    if (SECURITY.equals(category)) {
      out.add(INAPP);
      out.add(EMAIL);
      return out;
    }
    if (prefs.muted().contains(category)) {
      return out;
    }
    out.add(INAPP);
    if (prefs.email()) {
      out.add(EMAIL);
    }
    if (wantsPush && prefs.push()) {
      out.add(PUSH);
    }
    return out;
  }
}
