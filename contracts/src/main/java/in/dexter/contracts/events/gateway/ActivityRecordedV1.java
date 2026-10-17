package in.dexter.contracts.events.gateway;

/**
 * {@code gateway.activity.recorded} v1: one sampled request through the gateway (P06; P18's activity analytics
 * consumes it). Envelope accountId = the verified caller, null when anonymous. {@code routePattern} is the matched
 * route pattern (never the raw path: no ids, low cardinality).
 */
public record ActivityRecordedV1(String requestId, String service, String routePattern, String routeClass,
    String method, int status, long durationMs, String occurredAt) {
  public static final String TYPE = "gateway.activity.recorded";
  public static final int VERSION = 1;
  public static final String TOPIC = "dexter.gateway.activity.v1";
}
