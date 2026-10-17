package in.dexter.ledger.infra.objectstore;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** SigV4 presigning against the AWS documentation's example (S3 API reference, "Authenticating Requests: Using Query Parameters"). */
class SigV4Test {

  @Test
  void matchesTheAwsDocumentationExample() {
    SigV4 s = new SigV4("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1");
    String url = s.presign("GET", "https://examplebucket.s3.amazonaws.com", "/test.txt", Duration.ofSeconds(86_400),
        Instant.parse("2013-05-24T00:00:00Z")).toString();
    assertThat(url).isEqualTo("https://examplebucket.s3.amazonaws.com/test.txt?X-Amz-Algorithm=AWS4-HMAC-SHA256"
        + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
        + "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host"
        + "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404");
  }

  @Test
  void pathStyleKeepsThePortAndEncodesKeys() {
    SigV4 s = new SigV4("k", "s", "us-east-1");
    String url = s.presign("PUT", "http://127.0.0.1:8333", "/dexter-imports/imports/a b.csv", Duration.ofMinutes(15),
        Instant.parse("2026-10-17T00:00:00Z")).toString();
    assertThat(url).startsWith("http://127.0.0.1:8333/dexter-imports/imports/a%20b.csv?X-Amz-Algorithm=");
    assertThat(url).contains("X-Amz-Expires=900");
  }
}
