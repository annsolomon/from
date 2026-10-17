package in.dexter.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.gateway.infra.ClientIp;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientIpTest {
  final ClientIp ip = new ClientIp(List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12"));

  static InetSocketAddress from(String a) {
    return new InetSocketAddress(a, 40000);
  }

  @Test
  void directClientsAreTheirSocketAddress() {
    assertThat(ip.resolve(from("203.0.113.9"), "1.2.3.4")).as("untrusted peer: XFF ignored").isEqualTo("203.0.113.9");
    assertThat(ip.resolve(from("127.0.0.1"), null)).isEqualTo("127.0.0.1");
  }

  @Test
  void behindTrustedProxiesTheRightMostUntrustedHopWins() {
    assertThat(ip.resolve(from("127.0.0.1"), "198.51.100.7")).isEqualTo("198.51.100.7");
    assertThat(ip.resolve(from("172.18.0.5"), "6.6.6.6, 198.51.100.7, 10.0.0.3")).as("spoofed left entry ignored")
        .isEqualTo("198.51.100.7");
    assertThat(ip.resolve(from("127.0.0.1"), "10.0.0.2, 172.17.0.1")).as("all trusted: left-most")
        .isEqualTo("10.0.0.2");
    assertThat(ip.resolve(from("127.0.0.1"), "evil.example, 198.51.100.7")).as("right-most untrusted is fine")
        .isEqualTo("198.51.100.7");
    assertThat(ip.resolve(from("127.0.0.1"), "198.51.100.7, not-an-ip")).as("garbage → socket address")
        .isEqualTo("127.0.0.1");
  }
}
