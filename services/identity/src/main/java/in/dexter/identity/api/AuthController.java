package in.dexter.identity.api;

import in.dexter.identity.app.AdminGuard;
import in.dexter.identity.app.AuthService;
import in.dexter.identity.app.ClientInfo;
import in.dexter.identity.app.EmailVerificationService;
import in.dexter.identity.app.PasswordService;
import in.dexter.identity.app.RegistrationService;
import in.dexter.identity.app.TokenPair;
import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.security.AuthContext;
import in.dexter.security.web.PublicEndpoint;
import in.dexter.security.web.RequiresStepUp;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/auth (MASTER_PLAN P04). Token responses are {@code Cache-Control: no-store} (RFC 6749 5.1). Sign-in and
 * registration never reveal whether an email exists. The client IP is the socket address until the gateway (P06)
 * forwards a trusted one.
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {
  private final RegistrationService registration;
  private final EmailVerificationService verification;
  private final AuthService auth;
  private final PasswordService passwords;

  public AuthController(RegistrationService registration, EmailVerificationService verification, AuthService auth,
      PasswordService passwords) {
    this.registration = registration;
    this.verification = verification;
    this.auth = auth;
    this.passwords = passwords;
  }

  /** Always 202 {"status":"accepted"}; Idempotency-Key required (scoped per client). */
  @PublicEndpoint
  @PostMapping("/register")
  public ResponseEntity<String> register(@IdempotencyKey(required = true) UUID key,
      @RequestBody AuthRequests.Register body) {
    return IdempotentResponses.toEntity(registration.register(key, body.command()));
  }

  @PublicEndpoint
  @PostMapping("/verify-email")
  public Map<String, String> verifyEmail(@RequestBody AuthRequests.Token body) {
    boolean activated = verification.verify(body.token());
    return Map.of("status", activated ? "verified" : "already_verified");
  }

  @PublicEndpoint
  @PostMapping("/login")
  public ResponseEntity<Object> login(@RequestBody AuthRequests.Login body, HttpServletRequest request,
      @RequestHeader(value = "User-Agent", required = false) String userAgent) {
    return switch (auth.login(body.command(), new ClientInfo(request.getRemoteAddr(), userAgent))) {
      case AuthService.Tokens t -> noStore(TokenResponse.of(t.pair()));
      case AuthService.MfaRequired m -> noStore(new MfaChallengeResponse(true, m.mfaToken(), m.expiresIn()));
    };
  }

  /** Second step when MFA is enrolled: {@code code} (TOTP) or {@code recoveryCode}. */
  @PublicEndpoint
  @PostMapping("/mfa/verify")
  public ResponseEntity<TokenResponse> verifyMfa(@RequestBody AuthRequests.MfaVerify body) {
    TokenPair p = auth.verifyMfa(body.mfaToken(), body.code(), body.recoveryCode());
    return noStore(TokenResponse.of(p));
  }

  /** Re-authentication for sensitive actions: password (+ second factor when enrolled) → a fresh access token. */
  @PostMapping("/step-up")
  public ResponseEntity<AccessTokenResponse> stepUp(AuthContext caller, @RequestBody AuthRequests.StepUp body) {
    AuthService.StepUp r = auth.stepUp(AdminGuard.requireUser(caller), body.password(), body.code(),
        body.recoveryCode());
    return noStore(new AccessTokenResponse(r.accessToken(), "Bearer", r.expiresIn()));
  }

  /** Login answer when a second factor is needed. */
  public record MfaChallengeResponse(boolean mfaRequired, String mfaToken, long expiresIn) {}

  public record AccessTokenResponse(String accessToken, String tokenType, long expiresIn) {}

  @PublicEndpoint
  @PostMapping("/token/refresh")
  public ResponseEntity<TokenResponse> refresh(@RequestBody AuthRequests.Refresh body) {
    return noStore(TokenResponse.of(auth.refresh(body.refreshToken())));
  }

  /** Signs out the session of this access token. */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(AuthContext caller) {
    auth.logout(AdminGuard.requireUser(caller));
    return ResponseEntity.noContent().build();
  }

  /** Signs out every session (step-up: recent sign-in required). */
  @RequiresStepUp
  @PostMapping("/logout-all")
  public ResponseEntity<Map<String, Integer>> logoutAll(AuthContext caller) {
    return ResponseEntity.ok(Map.of("sessionsRevoked", auth.logoutAll(AdminGuard.requireUser(caller))));
  }

  /** Always 202, whether or not the email exists. */
  @PublicEndpoint
  @PostMapping("/password/forgot")
  public ResponseEntity<Map<String, String>> forgot(@RequestBody AuthRequests.Forgot body) {
    passwords.forgot(body.email());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "accepted"));
  }

  @PublicEndpoint
  @PostMapping("/password/reset")
  public Map<String, String> reset(@RequestBody AuthRequests.Reset body) {
    passwords.reset(body.token(), body.newPassword());
    return Map.of("status", "password_reset");
  }

  /** Step-up; keeps this session, signs out the others. */
  @RequiresStepUp
  @PostMapping("/password/change")
  public ResponseEntity<Void> change(AuthContext caller, @RequestBody AuthRequests.Change body) {
    passwords.change(AdminGuard.requireUser(caller), body.currentPassword(), body.newPassword());
    return ResponseEntity.noContent().build();
  }

  private static <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
  }
}
