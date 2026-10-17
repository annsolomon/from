package in.dexter.gateway.infra;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * The client's IP: the right-most {@code X-Forwarded-For} entry that is not a trusted proxy, else the socket
 * address. Entries a client adds itself sit left of the first trusted hop and are never believed.
 */
public final class ClientIp {
  private record Cidr(byte[] net, int bits) {
    boolean contains(byte[] a) {
      if (a.length != net.length) {
        return false;
      }
      int full = bits / 8;
      for (int i = 0; i < full; i++) {
        if (a[i] != net[i]) {
          return false;
        }
      }
      int rest = bits % 8;
      if (rest == 0) {
        return true;
      }
      int mask = (0xff << (8 - rest)) & 0xff;
      return (a[full] & mask) == (net[full] & mask);
    }
  }

  private final List<Cidr> trusted = new ArrayList<>();

  public ClientIp(List<String> trustedCidrs) {
    for (String c : trustedCidrs) {
      String[] p = c.split("/");
      byte[] net = literal(p[0]);
      if (net == null) {
        throw new IllegalArgumentException("not an IP literal: " + c);
      }
      trusted.add(new Cidr(net, p.length > 1 ? Integer.parseInt(p[1]) : net.length * 8));
    }
  }

  public String resolve(InetSocketAddress remote, String xForwardedFor) {
    String peer = remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    if (!isTrusted(peer) || xForwardedFor == null || xForwardedFor.isBlank()) {
      return peer;
    }
    String[] hops = xForwardedFor.split(",");
    for (int i = hops.length - 1; i >= 0; i--) {
      String h = hops[i].strip();
      if (literal(h) == null) {
        return peer; // garbage in the chain: do not trust any of it
      }
      if (!isTrusted(h)) {
        return h;
      }
    }
    return hops[0].strip();
  }

  boolean isTrusted(String ip) {
    byte[] a = literal(ip);
    if (a == null) {
      return false;
    }
    for (Cidr c : trusted) {
      if (c.contains(a)) {
        return true;
      }
    }
    return false;
  }

  /** Bytes of an IPv4/IPv6 literal (never a DNS lookup), or null. */
  static byte[] literal(String s) {
    if (s == null || s.isEmpty() || !(s.indexOf(':') >= 0 || s.matches("[0-9.]+"))) {
      return null;
    }
    if (s.indexOf(':') >= 0 && !s.matches("[0-9A-Fa-f:.%a-zA-Z]+")) {
      return null;
    }
    try {
      return InetAddress.getByName(s).getAddress();
    } catch (UnknownHostException | SecurityException e) {
      return null;
    }
  }
}
