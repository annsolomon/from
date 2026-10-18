package in.dexter.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.catalog.domain.FetchPolicy.Refusal;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The SSRF policy (MASTER_PLAN 7.3): URL rules before DNS, address rules after (pure). */
class FetchPolicyTest {
  static final FetchPolicy P = new FetchPolicy(List.of("hdfcbank.com", "partner.example:8443"),
      List.of("127.0.0.1:18091"));

  static Optional<Refusal> url(String u) {
    return P.checkUrl(u);
  }

  static InetAddress ip(String s) {
    try {
      return InetAddress.getByName(s);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void urlRules() {
    assertThat(url("https://hdfcbank.com/cards")).isEmpty();
    assertThat(url("https://www.hdfcbank.com/cards")).as("subdomain").isEmpty();
    assertThat(url("https://evilhdfcbank.com/")).contains(Refusal.HOST_NOT_ALLOWED);
    assertThat(url("https://hdfcbank.com.evil.io/")).contains(Refusal.HOST_NOT_ALLOWED);
    assertThat(url("http://hdfcbank.com/")).contains(Refusal.SCHEME_NOT_ALLOWED);
    assertThat(url("ftp://hdfcbank.com/")).contains(Refusal.SCHEME_NOT_ALLOWED);
    assertThat(url("file:///etc/passwd")).contains(Refusal.BAD_URL);
    assertThat(url("https://user:pw@hdfcbank.com/")).contains(Refusal.CREDENTIALS_IN_URL);
    assertThat(url("https://hdfcbank.com:8443/")).contains(Refusal.PORT_NOT_ALLOWED);
    assertThat(url("https://partner.example:8443/x")).as("entry with its port").isEmpty();
    assertThat(url("https://partner.example/x")).contains(Refusal.HOST_NOT_ALLOWED);
    assertThat(url("https://127.0.0.1/")).as("IP literals are never allowlisted").contains(Refusal.HOST_NOT_ALLOWED);
    assertThat(url("https://[::1]/")).contains(Refusal.HOST_NOT_ALLOWED);
    assertThat(url("https://169.254.169.254/latest/meta-data/")).contains(Refusal.HOST_NOT_ALLOWED);
    assertThat(url("https://localhost/")).contains(Refusal.HOST_NOT_ALLOWED);
    assertThat(url("http://127.0.0.1:18091/page")).as("the dev host: http + private allowed").isEmpty();
    assertThat(url("http://127.0.0.1:18092/page")).contains(Refusal.SCHEME_NOT_ALLOWED);
    assertThat(url("not a url")).contains(Refusal.BAD_URL);
  }

  @Test
  void everyResolvedAddressMustBePublic() {
    URI u = URI.create("https://www.hdfcbank.com/");
    assertThat(P.checkAddresses(u, List.of(ip("93.184.215.14")))).isEmpty();
    assertThat(P.checkAddresses(u, List.of(ip("2606:2800:21f:cb07:6820:80da:af6b:8b2c")))).isEmpty();
    assertThat(P.checkAddresses(u, List.of(ip("93.184.215.14"), ip("10.1.2.3"))))
        .as("one private answer is enough to refuse").contains(Refusal.PRIVATE_ADDRESS);
    assertThat(P.checkAddresses(u, List.of())).contains(Refusal.PRIVATE_ADDRESS);
    assertThat(P.checkAddresses(URI.create("http://127.0.0.1:18091/"), List.of(ip("127.0.0.1")))).isEmpty();
  }

  @Test
  void privateAndSpecialRanges() {
    for (String a : List.of("0.0.0.0", "10.0.0.1", "127.0.0.1", "127.255.0.9", "100.64.0.1", "100.127.255.254",
        "169.254.169.254", "172.16.0.1", "172.31.255.255", "192.0.0.8", "192.0.2.1", "192.88.99.1", "192.168.1.1",
        "198.18.0.1", "198.51.100.7", "203.0.113.9", "224.0.0.1", "240.0.0.1", "255.255.255.255",
        "::", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "fec0::1", "ff02::1", "2001:db8::1",
        "::ffff:10.0.0.1", "::ffff:169.254.169.254", "::127.0.0.1", "64:ff9b::a9fe:a9fe", "2002:c0a8:0101::1")) {
      assertThat(FetchPolicy.isPublic(ip(a))).as(a).isFalse();
    }
    for (String a : List.of("8.8.8.8", "1.1.1.1", "100.128.0.1", "172.32.0.1", "192.169.0.1", "2606:4700::1111",
        "::ffff:8.8.8.8", "64:ff9b::808:808", "2002:0808:0808::1")) {
      assertThat(FetchPolicy.isPublic(ip(a))).as(a).isTrue();
    }
  }

  @Test
  void hostKeysAndSitemaps() {
    assertThat(FetchPolicy.hostKey(URI.create("https://WWW.HDFCbank.com:443/x"))).isEqualTo("www.hdfcbank.com");
    assertThat(FetchPolicy.hostKey(URI.create("http://127.0.0.1:18091/x"))).isEqualTo("127.0.0.1:18091");
    String xml = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><urlset>"
        + "<url><loc>https://a.issuer.test/x</loc></url><url><loc><![CDATA[https://a.issuer.test/y]]></loc></url>"
        + "<url><loc>https://b.issuer.test/z</loc></url><url><loc> https://a.issuer.test/x </loc></url>"
        + "<url><loc>http://a.issuer.test/w</loc></url><url><loc>&e;</loc></url>"
        + "<url><loc>https://u:p@a.issuer.test/v</loc></url></urlset>";
    assertThat(Sitemaps.pageUrls(xml, URI.create("https://a.issuer.test/sitemap.xml"), 10))
        .as("own host and scheme only, no duplicates, entities never resolved")
        .containsExactly("https://a.issuer.test/x", "https://a.issuer.test/y");
    assertThat(Sitemaps.pageUrls(xml, URI.create("https://a.issuer.test/sitemap.xml"), 1)).hasSize(1);
  }
}
