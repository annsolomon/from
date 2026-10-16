package in.dexter.idempotency;

import java.util.Map;

/**
 * The response to send (MASTER_PLAN 5.1). {@code body} is the stored JSON text (as Postgres
 * normalised it), so the first response and every replay are byte-identical; {@code replayed} adds
 * {@code Idempotent-Replayed: true}.
 */
public record IdempotentResponse(int status, Map<String, String> headers, String body, boolean replayed) {
  public static final String REPLAYED_HEADER = "Idempotent-Replayed";

  public IdempotentResponse {
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }
}
