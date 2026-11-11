package in.dexter.privacy;

import in.dexter.contracts.events.audit.PrivacyRequestedV1;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** The pure rules of a participant: which requests it answers, which function runs a phase, where a part goes. */
public final class PrivacyRequests {
  /** The inbox consumer name (each service's own inbox table). */
  public static final String CONSUMER = "privacy";
  /** Phases and the SQL function each one calls ({@code <schema>.privacy_<phase>(uuid)}). */
  static final Map<String, String> FUNCTIONS = Map.of(
      PrivacyRequestedV1.FREEZE, "privacy_freeze",
      PrivacyRequestedV1.ERASE, "privacy_erase",
      PrivacyRequestedV1.SHRED, "privacy_shred",
      PrivacyRequestedV1.EXPORT, "privacy_export");

  private PrivacyRequests() {}

  /**
   * The Kafka consumer of {@code service} on {@code dexter.audit.privacy.v1}: {@code privacy-<svc>}, so each
   * service's retry stages and DLQ are its own ({@code dexter.audit.privacy.v1.privacy-<svc>.retry-1m}, ...) and a
   * retry never reaches another service's consumer.
   */
  public static String consumer(String service) {
    return "privacy-" + service;
  }

  /** True when {@code service} is asked and runs {@code request.phase()} (identity alone freezes and shreds). */
  public static boolean appliesTo(PrivacyRequestedV1 request, String service, Set<String> phases) {
    return request.services() != null && request.services().contains(service) && phases.contains(request.phase())
        && FUNCTIONS.containsKey(request.phase());
  }

  /** {@code <schema>.privacy_<phase>}; the schema and phase are validated identifiers, never user input. */
  public static String function(String schema, String phase) {
    String fn = FUNCTIONS.get(phase);
    if (fn == null) {
      throw new IllegalArgumentException("unknown privacy phase: " + phase);
    }
    if (!schema.matches("[a-z][a-z0-9_]{0,31}")) {
      throw new IllegalArgumentException("bad schema: " + schema);
    }
    return schema + "." + fn;
  }

  /** {@code privacy/<requestId>/<service>.json}: one part per service, overwritten by a retried export. */
  public static String partKey(UUID requestId, String service) {
    return "privacy/" + requestId + "/" + service + ".json";
  }

  /** Rows in an export: the sum of its tables' array lengths. */
  public static long rows(JsonNode export) {
    long n = 0;
    for (JsonNode table : export) {
      if (table.isArray()) {
        n += table.size();
      }
    }
    return n;
  }
}
