package in.dexter.observability;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.events.EventTracing;
import org.junit.jupiter.api.Test;

class TraceparentTest {
  @Test
  void idsArePaddedToTheW3cWidths() {
    assertThat(MicrometerEventTracing.pad("abc", 16)).isEqualTo("0000000000000abc");
    assertThat(MicrometerEventTracing.pad("0af7651916cd43dd8448eb211c80319c", 32))
        .isEqualTo("0af7651916cd43dd8448eb211c80319c");
  }

  @Test
  void withoutAnInstalledTracerTheLibraryIsANoOp() throws Exception {
    EventTracing.reset();
    assertThat(EventTracing.traceparentSupplier().get()).isNull();
    boolean[] ran = {false};
    EventTracing.current().consume("c", null, () -> ran[0] = true);
    assertThat(ran[0]).isTrue();
  }
}
