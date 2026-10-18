package in.dexter.objectstore;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 query-string presigning for S3-compatible stores (ADR-0003: the app talks only the S3
 * API; SeaweedFS in dev, any S3 in prod). Path-style URLs ({@code endpoint/bucket/key}); only the {@code host}
 * header is signed and the payload is {@code UNSIGNED-PAYLOAD}, so a browser can PUT the file directly.
 * Verified against the AWS documentation's presigned-URL example ({@code SigV4Test}).
 */
public final class SigV4 {
  private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
      .withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

  private final String accessKey;
  private final String secretKey;
  private final String region;

  public SigV4(String accessKey, String secretKey, String region) {
    this.accessKey = accessKey;
    this.secretKey = secretKey;
    this.region = region;
  }

  /**
   * @param endpoint scheme + host[:port] (+ optional base path), e.g. {@code http://127.0.0.1:8333}
   * @param path     the object path, starting with {@code /} ({@code /bucket/key}); encoded here
   */
  public URI presign(String method, String endpoint, String path, Duration ttl, Instant now) {
    URI base = URI.create(endpoint);
    String host = base.getPort() < 0 ? base.getHost() : base.getHost() + ":" + base.getPort();
    String basePath = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
    String canonicalUri = basePath + encodePath(path);
    String amzDate = AMZ_DATE.format(now);
    String day = DAY.format(now);
    String scope = day + "/" + region + "/s3/aws4_request";
    Map<String, String> q = new TreeMap<>();
    q.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
    q.put("X-Amz-Credential", accessKey + "/" + scope);
    q.put("X-Amz-Date", amzDate);
    q.put("X-Amz-Expires", Long.toString(ttl.toSeconds()));
    q.put("X-Amz-SignedHeaders", "host");
    StringBuilder query = new StringBuilder();
    q.forEach((k, v) -> {
      if (!query.isEmpty()) {
        query.append('&');
      }
      query.append(encode(k)).append('=').append(encode(v));
    });
    String canonicalRequest = method + "\n" + canonicalUri + "\n" + query + "\n" + "host:" + host + "\n\n" + "host\n"
        + "UNSIGNED-PAYLOAD";
    String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonicalRequest));
    byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day);
    key = hmac(key, region);
    key = hmac(key, "s3");
    key = hmac(key, "aws4_request");
    String signature = hex(hmac(key, toSign));
    return URI.create(base.getScheme() + "://" + host + canonicalUri + "?" + query + "&X-Amz-Signature=" + signature);
  }

  /** RFC 3986 unreserved characters stay; everything else is percent-encoded (AWS UriEncode). */
  static String encode(String s) {
    StringBuilder sb = new StringBuilder();
    for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xff);
      if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.'
          || c == '~') {
        sb.append(c);
      } else {
        sb.append('%').append(String.format("%02X", b & 0xff));
      }
    }
    return sb.toString();
  }

  static String encodePath(String path) {
    StringBuilder sb = new StringBuilder();
    for (String seg : path.split("/", -1)) {
      if (!sb.isEmpty() || path.startsWith("/")) {
        sb.append('/');
      }
      sb.append(encode(seg));
    }
    String out = sb.toString();
    return out.startsWith("//") ? out.substring(1) : out;
  }

  private static byte[] sha256(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] hmac(byte[] key, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hex(byte[] b) {
    return HexFormat.of().formatHex(b);
  }
}
