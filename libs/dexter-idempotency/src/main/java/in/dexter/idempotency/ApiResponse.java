package in.dexter.idempotency;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What a use-case returns from the work inside {@link IdempotencyEngine}: status, the headers worth
 * replaying (for example {@code Location}, {@code ETag}) and a body the engine serialises with the
 * service's JSON mapper. A null body means no content.
 */
public record ApiResponse(int status, Map<String, String> headers, Object body) {
  public ApiResponse {
    if (status < 200 || status > 599) {
      throw new IllegalArgumentException("status out of range: " + status);
    }
    headers = headers == null ? Map.of() : Map.copyOf(new TreeMap<>(headers));
    for (String k : headers.keySet()) {
      if (!k.matches("[A-Za-z0-9-]{1,64}")) {
        throw new IllegalArgumentException("bad header name: " + k);
      }
    }
  }

  public static ApiResponse of(int status, Object body) {
    return new ApiResponse(status, Map.of(), body);
  }

  public static ApiResponse created(String location, Object body) {
    return new ApiResponse(201, Map.of("Location", Objects.requireNonNull(location)), body);
  }
}
