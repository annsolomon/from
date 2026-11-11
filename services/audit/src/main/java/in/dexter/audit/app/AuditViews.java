package in.dexter.audit.app;

import in.dexter.audit.app.NotificationStore.Notification;
import in.dexter.audit.domain.ChainVerifier;
import in.dexter.audit.domain.Templates;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Response bodies. */
public final class AuditViews {
  private AuditViews() {}

  public record EntryView(long seq, String occurredAt, UUID id, String actorType, @Nullable String actorId,
      String action, @Nullable String targetType, @Nullable String targetId, @Nullable String requestId,
      JsonNode details, String hash) {}

  public record VerifyView(boolean ok, long checked, long headSeq, String headHash, @Nullable Long brokenAt,
      @Nullable String reason, int anchorsChecked, int anchorsExportedChecked, long durationMs) {
    static VerifyView of(ChainVerifier.Result r, int exported, long ms) {
      return new VerifyView(r.ok(), r.checked(), r.headSeq(), r.headHash(), r.brokenAt(), r.reason(),
          r.anchorsChecked(), exported, ms);
    }
  }

  public record NotificationView(UUID id, String category, String template, String title, String body,
      Map<String, Object> payload, String createdAt, @Nullable String readAt) {
    static NotificationView of(Notification n) {
      Templates.Text t = Templates.render(n.template(), n.payload());
      return new NotificationView(n.id(), n.category(), n.template(), t.title(), t.body(), n.payload(),
          n.createdAt().toString(), n.readAt() == null ? null : n.readAt().toString());
    }
  }

  public record PrefsView(boolean inapp, boolean email, boolean push, List<String> muted, List<String> categories,
      List<String> mutable, int version) {}

  public record PrivacyView(UUID id, String kind, String status, String phase, int attempt, List<String> services,
      JsonNode steps, String createdAt, @Nullable String completedAt, @Nullable String error,
      @Nullable String downloadUrl, @Nullable String downloadExpiresAt, @Nullable Long archiveBytes) {}

  public record AdminPrivacyView(UUID id, @Nullable UUID accountId, @Nullable String subjectRef, String kind,
      String status, String phase, int attempt, List<String> services, JsonNode steps, String phaseStartedAt,
      @Nullable String nextCheckAt, String createdAt, @Nullable String completedAt, @Nullable String error,
      int version) {}

  public record DashboardView(String from, String to, int hours, Totals totals, List<ServiceRow> services,
      List<HourRow> hourly) {}

  public record Totals(long requests, long clientErrors, long serverErrors, long avgMs, int maxMs) {}

  public record ServiceRow(String service, long requests, long clientErrors, long serverErrors, long avgMs,
      int maxMs) {}

  public record HourRow(String hour, long requests, long clientErrors, long serverErrors, long avgMs) {}
}
