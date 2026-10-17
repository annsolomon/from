package in.dexter.identity.api;

import in.dexter.identity.app.AdminGuard;
import in.dexter.identity.app.MfaService;
import in.dexter.security.AuthContext;
import in.dexter.security.web.RequiresStepUp;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/me/mfa (MASTER_PLAN P05). Enrolment, removal and new recovery codes need step-up (recent sign-in, with the
 * second factor once enrolled). Secrets and recovery codes are returned once and never cached.
 */
@RestController
@RequestMapping("/v1/me/mfa")
public class MfaController {
  private final MfaService mfa;

  public MfaController(MfaService mfa) {
    this.mfa = mfa;
  }

  @GetMapping
  public MfaService.Status status(AuthContext caller) {
    return mfa.status(AdminGuard.requireUser(caller));
  }

  /** A new (pending) TOTP secret + otpauth URI for the authenticator app. */
  @RequiresStepUp
  @PostMapping("/totp/enroll")
  public ResponseEntity<MfaService.Enrollment> enroll(AuthContext caller) {
    return noStore(mfa.enroll(AdminGuard.requireUser(caller)));
  }

  /** The first code from the app confirms it; the 10 recovery codes are shown only in this response. */
  @PostMapping("/totp/confirm")
  public ResponseEntity<Map<String, List<String>>> confirm(AuthContext caller,
      @RequestBody AuthRequests.MfaCode body) {
    return noStore(Map.of("recoveryCodes", mfa.confirm(AdminGuard.requireUser(caller), body.code())));
  }

  @RequiresStepUp
  @DeleteMapping("/totp")
  public ResponseEntity<Void> disable(AuthContext caller) {
    mfa.disable(AdminGuard.requireUser(caller));
    return ResponseEntity.noContent().build();
  }

  /** Replaces all recovery codes (the old ones stop working). */
  @RequiresStepUp
  @PostMapping("/recovery-codes")
  public ResponseEntity<Map<String, List<String>>> regenerate(AuthContext caller) {
    return noStore(Map.of("recoveryCodes", mfa.regenerate(AdminGuard.requireUser(caller))));
  }

  static <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
  }
}
