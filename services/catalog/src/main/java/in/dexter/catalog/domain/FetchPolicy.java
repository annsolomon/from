package in.dexter.catalog.domain;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the ingestion collector may fetch (MASTER_PLAN 7.3 "SSRF-safe fetcher"). A URL is allowed when its scheme is
 * https (http only for dev hosts), it carries no credentials, its host is on the allowlist (the host itself or a
 * subdomain of an entry; an entry with a port matches that port only) and its port is the scheme's default (or the
 * entry's). Every address the host resolves to must be public: loopback, private, link-local (169.254.x, the cloud
 * metadata range), CGNAT, unique-local, multicast, unspecified, reserved and documentation ranges are refused,
 * including IPv4 addresses embedded in IPv6 (mapped, NAT64, 6to4). Dev hosts ({@code host:port} entries of
 * {@code devHosts}) are the one exception: they may use http and resolve to a private address (a local mock issuer
 * site); production configures none. Pure: resolution happens in the fetcher, which passes the addresses in.
 */
public final class FetchPolicy {
  /** Why a URL or address is refused (the run checkpoint and the API name these codes). */
  public enum Refusal {
    BAD_URL, SCHEME_NOT_ALLOWED, CREDENTIALS_IN_URL, HOST_NOT_ALLOWED, PORT_NOT_ALLOWED, PRIVATE_ADDRESS
  }

  private final Set<String> allowedHosts;
  private final Set<String> devHosts;

  /**
   * @param allowedHosts lower-case host names ({@code hdfcbank.com} allows {@code www.hdfcbank.com} too) or
   *                     {@code host:port}
   * @param devHosts     exact {@code host:port} entries allowed over http and on private addresses (dev/test only)
   */
  public FetchPolicy(List<String> allowedHosts, List<String> devHosts) {
    this.allowedHosts = clean(allowedHosts);
    this.devHosts = clean(devHosts);
  }

  private static Set<String> clean(List<String> hosts) {
    return hosts == null ? Set.of() : hosts.stream().filter(h -> h != null && !h.isBlank())
        .map(h -> h.strip().toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
  }

  /** {@code host} or {@code host:port} (non-default port) of a URL, lower case; the key sources store. */
  public static String hostKey(URI u) {
    String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
    int port = u.getPort();
    int def = "http".equalsIgnoreCase(u.getScheme()) ? 80 : 443;
    return port < 0 || port == def ? host : host + ":" + port;
  }

  /** Parses and checks a URL before any network access. */
  public Optional<Refusal> checkUrl(String raw) {
    URI u;
    try {
      u = new URI(raw);
    } catch (Exception e) {
      return Optional.of(Refusal.BAD_URL);
    }
    return checkUrl(u);
  }

  public Optional<Refusal> checkUrl(URI u) {
    if (u.getScheme() == null || u.getHost() == null || u.getHost().isBlank() || u.isOpaque()) {
      return Optional.of(Refusal.BAD_URL);
    }
    String scheme = u.getScheme().toLowerCase(Locale.ROOT);
    if (u.getRawUserInfo() != null) {
      return Optional.of(Refusal.CREDENTIALS_IN_URL);
    }
    String key = hostKey(u);
    boolean dev = devHosts.contains(key);
    if (!scheme.equals("https") && !(scheme.equals("http") && dev)) {
      return Optional.of(Refusal.SCHEME_NOT_ALLOWED);
    }
    if (dev) {
      return Optional.empty();
    }
    String host = u.getHost().toLowerCase(Locale.ROOT);
    if (host.startsWith("[") || isIpLiteral(host)) {
      return Optional.of(Refusal.HOST_NOT_ALLOWED); // IP literals are never on the allowlist
    }
    boolean defaultPort = u.getPort() < 0 || u.getPort() == 443;
    if (allowedHosts.contains(key)) {
      return Optional.empty();
    }
    for (String entry : allowedHosts) {
      if (!entry.contains(":") && (host.equals(entry) || host.endsWith("." + entry))) {
        return defaultPort ? Optional.empty() : Optional.of(Refusal.PORT_NOT_ALLOWED);
      }
    }
    return Optional.of(Refusal.HOST_NOT_ALLOWED);
  }

  /** Checks every resolved address of the URL's host (an empty list is refused). */
  public Optional<Refusal> checkAddresses(URI u, List<InetAddress> addresses) {
    if (devHosts.contains(hostKey(u))) {
      return Optional.empty();
    }
    if (addresses == null || addresses.isEmpty()) {
      return Optional.of(Refusal.PRIVATE_ADDRESS);
    }
    for (InetAddress a : addresses) {
      if (!isPublic(a)) {
        return Optional.of(Refusal.PRIVATE_ADDRESS);
      }
    }
    return Optional.empty();
  }

  static boolean isIpLiteral(String host) {
    return host.matches("[0-9.]+") || host.contains(":");
  }

  /** True for a globally routable unicast address. */
  public static boolean isPublic(InetAddress a) {
    if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
        || a.isMulticastAddress()) {
      return false;
    }
    byte[] b = a.getAddress();
    if (a instanceof Inet4Address) {
      return isPublicV4(b);
    }
    if (a instanceof Inet6Address) {
      int b0 = b[0] & 0xff;
      int b1 = b[1] & 0xff;
      if ((b0 & 0xfe) == 0xfc) {
        return false; // fc00::/7 unique local
      }
      if (b0 == 0xfe && (b1 & 0xc0) == 0xc0) {
        return false; // fec0::/10 site local (deprecated)
      }
      if (b0 == 0x20 && b1 == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
        return false; // 2001:db8::/32 documentation
      }
      boolean zeroPrefix = true;
      for (int i = 0; i < 10; i++) {
        zeroPrefix &= b[i] == 0;
      }
      if (zeroPrefix && ((b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff || b[10] == 0 && b[11] == 0)) {
        return isPublicV4(new byte[] {b[12], b[13], b[14], b[15]}); // ::ffff:a.b.c.d mapped, ::a.b.c.d compatible
      }
      if (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) {
        return isPublicV4(new byte[] {b[12], b[13], b[14], b[15]}); // 64:ff9b::/96 NAT64
      }
      if (b0 == 0x20 && b1 == 0x02) {
        return isPublicV4(new byte[] {b[2], b[3], b[4], b[5]}); // 2002::/16 6to4
      }
      return true;
    }
    return false;
  }

  static boolean isPublicV4(byte[] b) {
    int a0 = b[0] & 0xff;
    int a1 = b[1] & 0xff;
    int a2 = b[2] & 0xff;
    return !(a0 == 0                                      // 0.0.0.0/8 "this network"
        || a0 == 10                                       // private
        || a0 == 127                                      // loopback
        || a0 == 100 && (a1 & 0xc0) == 64                 // 100.64.0.0/10 CGNAT
        || a0 == 169 && a1 == 254                         // link local, cloud metadata
        || a0 == 172 && (a1 & 0xf0) == 16                 // private
        || a0 == 192 && a1 == 0 && (a2 == 0 || a2 == 2)   // IETF protocol assignments, TEST-NET-1
        || a0 == 192 && a1 == 88 && a2 == 99              // 6to4 relay anycast
        || a0 == 192 && a1 == 168                         // private
        || a0 == 198 && (a1 & 0xfe) == 18                 // benchmarking
        || a0 == 198 && a1 == 51 && a2 == 100             // TEST-NET-2
        || a0 == 203 && a1 == 0 && a2 == 113              // TEST-NET-3
        || a0 >= 224);                                    // multicast, reserved, broadcast
  }
}
