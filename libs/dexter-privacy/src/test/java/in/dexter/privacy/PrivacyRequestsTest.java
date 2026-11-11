package in.dexter.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.contracts.events.audit.PrivacyRequestedV1;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PrivacyRequestsTest {
  static final UUID REQUEST = UUID.fromString("0192f1e2-0000-7000-8000-000000000a01");

  static PrivacyRequestedV1 request(String phase, List<String> services) {
    return new PrivacyRequestedV1(REQUEST, "delete", phase, 1, services, "2026-11-11T10:00:00Z");
  }

  @Test
  void aServiceAnswersOnlyThePhasesItRunsWhenItIsAsked() {
    Set<String> ledger = Set.of("erase", "export");
    Set<String> identity = Set.of("freeze", "erase", "shred", "export");
    assertThat(PrivacyRequests.appliesTo(request("erase", List.of("ledger", "reco")), "ledger", ledger)).isTrue();
    assertThat(PrivacyRequests.appliesTo(request("erase", List.of("reco")), "ledger", ledger))
        .as("a retry names only the services that have not answered").isFalse();
    assertThat(PrivacyRequests.appliesTo(request("freeze", List.of("identity", "ledger")), "ledger", ledger))
        .as("only identity freezes").isFalse();
    assertThat(PrivacyRequests.appliesTo(request("freeze", List.of("identity")), "identity", identity)).isTrue();
    assertThat(PrivacyRequests.appliesTo(request("rename", List.of("identity")), "identity",
        Set.of("rename"))).as("an unknown phase is never run").isFalse();
  }

  @Test
  void functionsAreTheServicesOwnAndNamesAreValidated() {
    assertThat(PrivacyRequests.function("portfolio", "erase")).isEqualTo("portfolio.privacy_erase");
    assertThat(PrivacyRequests.function("identity", "shred")).isEqualTo("identity.privacy_shred");
    assertThatThrownBy(() -> PrivacyRequests.function("ledger", "drop")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PrivacyRequests.function("x; drop table y", "erase"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void partKeysAndRowCounts() {
    assertThat(PrivacyRequests.partKey(REQUEST, "billing"))
        .isEqualTo("privacy/0192f1e2-0000-7000-8000-000000000a01/billing.json");
    var json = JsonMapper.builder().build().readTree("{\"expenses\":[{},{},{}],\"categories\":[],\"rules\":[{}]}");
    assertThat(PrivacyRequests.rows(json)).isEqualTo(4);
  }

  @Test
  void theAcknowledgementIsTheServicesOwnEvent() {
    var t = PrivacyParticipant.completedType("commerce");
    assertThat(t.type()).isEqualTo("commerce.privacy.completed");
    assertThat(t.topic()).isEqualTo("dexter.commerce.privacy.v1");
    assertThat(PrivacyAutoConfiguration.topics("commerce", List.of("retry-1m", "retry-10m"), 3, (short) 1).specs())
        .extracting(s -> s.name())
        .contains("dexter.commerce.privacy.v1", "dexter.audit.privacy.v1",
            "dexter.audit.privacy.v1.privacy-commerce.retry-1m", "dexter.audit.privacy.v1.privacy-commerce.dlq");
  }
}
