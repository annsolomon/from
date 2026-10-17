package in.dexter.identity.api;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.db.page.PageRequest;
import in.dexter.identity.app.AdminGuard;
import in.dexter.identity.app.MeView;
import in.dexter.identity.app.ProfilePatch;
import in.dexter.identity.app.ProfileService;
import in.dexter.identity.app.SessionService;
import in.dexter.identity.app.SessionStore.SessionView;
import in.dexter.security.AuthContext;
import in.dexter.web.etag.ETags;
import in.dexter.web.etag.IfMatchVersion;
import in.dexter.web.page.PageResponse;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** /v1/me (profile, ETag + If-Match) and /v1/me/sessions (keyset). The account is always the token's subject. */
@RestController
@RequestMapping("/v1/me")
public class MeController {
  static final String MERGE_PATCH = "application/merge-patch+json";
  static final Set<String> PATCHABLE = Set.of("displayName", "tz", "phone");

  private final ProfileService profiles;
  private final SessionService sessions;

  public MeController(ProfileService profiles, SessionService sessions) {
    this.profiles = profiles;
    this.sessions = sessions;
  }

  @GetMapping
  public ResponseEntity<MeView> me(AuthContext caller) {
    MeView v = profiles.me(AdminGuard.requireUser(caller));
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  /** JSON Merge Patch (RFC 7386) of displayName, tz, phone; requires {@code If-Match}. */
  @PatchMapping(consumes = {MERGE_PATCH, "application/json"})
  public ResponseEntity<MeView> patch(AuthContext caller, @IfMatchVersion long version, @RequestBody JsonNode body) {
    MeView v = profiles.patch(AdminGuard.requireUser(caller), version, toPatch(body));
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @GetMapping("/sessions")
  public PageResponse<SessionItem> sessions(AuthContext caller, PageRequest page) {
    UUID current = caller.sessionId() == null ? null : UUID.fromString(caller.sessionId());
    return PageResponse.from(sessions.list(AdminGuard.requireUser(caller), page), s -> SessionItem.of(s, current));
  }

  @DeleteMapping("/sessions/{id}")
  public ResponseEntity<Void> revokeSession(AuthContext caller, @PathVariable("id") UUID id) {
    sessions.revoke(AdminGuard.requireUser(caller), id);
    return ResponseEntity.noContent().build();
  }

  /** One open session; {@code current} marks the session of the calling token. */
  public record SessionItem(UUID id, String clientId, String deviceLabel, String ipFirst, Instant createdAt,
      Instant lastSeenAt, boolean current) {
    static SessionItem of(SessionView s, UUID current) {
      return new SessionItem(s.id(), s.clientId(), s.deviceLabel(), s.ipFirst(), s.createdAt(), s.lastSeenAt(),
          s.id().equals(current));
    }
  }

  static ProfilePatch toPatch(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new DexterException(CoreErrors.BAD_REQUEST, "The body must be a JSON object (merge patch)");
    }
    for (String name : body.propertyNames()) {
      if (!PATCHABLE.contains(name)) {
        throw new DexterException(CoreErrors.UNKNOWN_FIELD, "Field '" + name + "' cannot be changed here");
      }
    }
    return new ProfilePatch(field(body, "displayName"), field(body, "tz"), field(body, "phone"));
  }

  private static ProfilePatch.Field field(JsonNode body, String name) {
    if (!body.has(name)) {
      return ProfilePatch.Field.ABSENT;
    }
    JsonNode n = body.get(name);
    if (n.isNull()) {
      return ProfilePatch.Field.of(null);
    }
    if (!n.isString()) {
      throw new DexterException(CoreErrors.BAD_REQUEST, "Field '" + name + "' must be a string or null");
    }
    return ProfilePatch.Field.of(n.stringValue());
  }
}
