package in.dexter.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.core.error.FieldError;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DomainRulesTest {
  static final PasswordPolicy.BreachedList BREACHED = p -> Set.of("password123456").contains(p.toLowerCase());

  @Test
  void emails() {
    assertThat(Emails.normalize("  Asha@Demo.IN ")).isEqualTo("asha@demo.in");
    assertThat(Emails.normalize("no-at-sign")).isNull();
    assertThat(Emails.normalize("a@nodot")).isNull();
    assertThat(Emails.normalize("a@b@c.d")).isNull();
    assertThat(Emails.normalize("x".repeat(65) + "@d.in")).isNull();
    assertThat(Emails.normalize(null)).isNull();
    assertThat(Emails.mask("asha@demo.in")).isEqualTo("a***@demo.in");
  }

  @Test
  void passwordPolicy() {
    assertThat(codes(PasswordPolicy.check("p", "short", null, BREACHED))).containsExactly("TOO_SHORT");
    assertThat(codes(PasswordPolicy.check("p", "x".repeat(129), null, BREACHED))).containsExactly("TOO_LONG");
    assertThat(codes(PasswordPolicy.check("p", "Password123456", null, BREACHED))).containsExactly("BREACHED");
    assertThat(codes(PasswordPolicy.check("p", "asha@demo.in-2026", "asha@demo.in", BREACHED)))
        .containsExactly("CONTAINS_EMAIL");
    assertThat(codes(PasswordPolicy.check("p", null, null, BREACHED))).containsExactly("REQUIRED");
    assertThat(PasswordPolicy.check("p", "Correct-Horse-9", "asha@demo.in", BREACHED)).isEmpty();
    // code points, not UTF-16 units: ten emoji are ten characters
    assertThat(PasswordPolicy.check("p", "🔒".repeat(10), null, BREACHED)).isEmpty();
  }

  @Test
  void profiles() {
    assertThat(Profiles.normalizePhone("98765 43210")).isEqualTo("+919876543210");
    assertThat(Profiles.normalizePhone("+1 (415) 555-0100")).isEqualTo("+14155550100");
    assertThat(Profiles.normalizePhone("12")).isNull();
    assertThat(Profiles.maskPhone("+919876543210")).isEqualTo("+91******3210");
    assertThat(Profiles.checkTz("tz", "Asia/Kolkata")).isNull();
    assertThat(Profiles.checkTz("tz", "UTC")).isNotNull();
    assertThat(Profiles.checkTz("tz", "Mars/Base")).isNotNull();
    assertThat(Profiles.checkDisplayName("n", "Asha")).isNull();
    assertThat(Profiles.checkDisplayName("n", "")).isNotNull();
    assertThat(Profiles.checkDisplayName("n", "a\u0007b")).isNotNull();
  }

  @Test
  void secretTokens() {
    String t = SecretTokens.newToken();
    assertThat(t).hasSize(43).matches("[A-Za-z0-9_-]+");
    assertThat(SecretTokens.looksValid(t)).isTrue();
    assertThat(SecretTokens.looksValid(t + "x")).isFalse();
    assertThat(SecretTokens.hash(t)).hasSize(32);
    assertThat(SecretTokens.newToken()).isNotEqualTo(t);
  }

  @Test
  void syntheticTenantsNeverLookLikeAccounts() {
    assertThat(IdentityTenants.SYSTEM.accountId().toString()).isEqualTo("00000000-0000-0000-0000-000000000000");
    assertThat(IdentityTenants.clientScope("dexter-app").version()).isEqualTo(3);
    assertThat(IdentityTenants.clientScope("dexter-app")).isNotEqualTo(IdentityTenants.clientScope("dexter-web"));
  }

  static List<String> codes(List<FieldError> errors) {
    return errors.stream().map(FieldError::code).toList();
  }
}
