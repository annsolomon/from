package in.dexter.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecoveryCodesTest {
  @Test
  void tenDistinctCodesInTheDisplayFormat() {
    List<String> codes = RecoveryCodes.generate();
    assertThat(codes).hasSize(RecoveryCodes.COUNT);
    assertThat(new HashSet<>(codes)).hasSize(RecoveryCodes.COUNT);
    assertThat(codes).allMatch(c -> c.matches("[a-z2-9]{5}-[a-z2-9]{5}"));
    assertThat(codes).noneMatch(c -> c.contains("0") || c.contains("1") || c.contains("l") || c.contains("o")
        || c.contains("i"));
  }

  @Test
  void normalizationAcceptsWhatUsersTypeAndRejectsTheRest() {
    assertThat(RecoveryCodes.normalize(" ABCDE-FGH23 ")).isEqualTo("abcdefgh23");
    assertThat(RecoveryCodes.normalize("abcde fgh23")).isEqualTo("abcdefgh23");
    assertThat(RecoveryCodes.normalize("abcdefgh2")).isNull();
    assertThat(RecoveryCodes.normalize("abcde-fgh21")).as("1 is not in the alphabet").isNull();
    assertThat(RecoveryCodes.normalize(null)).isNull();
  }
}
