package in.dexter.identity.api;

import in.dexter.identity.app.AdminAuthService;
import in.dexter.identity.app.AdminGuard;
import in.dexter.identity.app.ClientInfo;
import in.dexter.identity.app.ElevationService;
import in.dexter.security.AuthContext;
import in.dexter.security.web.PublicEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin console sign-in (MASTER_PLAN P05, 7.2): password → mandatory TOTP (enrolled at the first sign-in) → admin
 * tokens; refresh with rotation and reuse detection; break-glass elevation. Separate from user sign-in: an admin
 * is not a user account and an admin token never works on user endpoints.
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminAuthController {
  private final AdminAuthService auth;
  private final ElevationService elevations;

  public AdminAuthController(AdminAuthService auth, ElevationService elevations) {
    this.auth = auth;
    this.elevations = elevations;
  }

  /** Always answers with a challenge: {@code setup=true} means the admin must enrol an authenticator first. */
  @PublicEndpoint
  @PostMapping("/auth/login")
  public ResponseEntity<AdminAuthService.Challenge> login(@RequestBody AuthRequests.AdminLogin body,
      HttpServletRequest request, @RequestHeader(value = "User-Agent", required = false) String userAgent) {
    return MfaController.noStore(auth.login(body.email(), body.password(),
        new ClientInfo(request.getRemoteAddr(), userAgent)));
  }

  @PublicEndpoint
  @PostMapping("/auth/mfa/setup")
  public ResponseEntity<AdminAuthService.Setup> setup(@RequestBody AuthRequests.AdminMfa body) {
    return MfaController.noStore(auth.setup(body.mfaToken()));
  }

  @PublicEndpoint
  @PostMapping("/auth/mfa/verify")
  public ResponseEntity<AdminTokens> verify(@RequestBody AuthRequests.AdminMfa body) {
    return MfaController.noStore(AdminTokens.of(auth.verify(body.mfaToken(), body.code())));
  }

  @PublicEndpoint
  @PostMapping("/auth/token/refresh")
  public ResponseEntity<AdminTokens> refresh(@RequestBody AuthRequests.Refresh body) {
    return MfaController.noStore(AdminTokens.of(auth.refresh(body.refreshToken())));
  }

  @PostMapping("/auth/logout")
  public ResponseEntity<Void> logout(AuthContext caller) {
    auth.logout(caller);
    return ResponseEntity.noContent().build();
  }

  /** Single-use invitation link → password (status active; MFA setup follows at the first sign-in). */
  @PublicEndpoint
  @PostMapping("/auth/invite/accept")
  public Map<String, String> acceptInvite(@RequestBody AuthRequests.InviteAccept body) {
    auth.acceptInvite(body.token(), body.password());
    return Map.of("status", "accepted");
  }

  /** Who the admin console is signed in as (from the token). */
  @GetMapping("/me")
  public AdminMe me(AuthContext caller) {
    AdminGuard.require(caller);
    return new AdminMe(caller.accountId(), Set.copyOf(caller.roles()), Set.copyOf(caller.permissions()),
        caller.roles().contains(in.dexter.core.tenant.Tenant.SUPER_ADMIN), caller.expiresAt().toString());
  }

  /** Break-glass: SUPER_ADMIN for at most 1 h; a reason (20-500 chars) and a fresh TOTP code. */
  @PostMapping("/elevations")
  public ResponseEntity<ElevationService.Elevated> elevate(AuthContext caller, @RequestBody AuthRequests.Elevate body) {
    return MfaController.noStore(elevations.start(caller, body.reason(), body.code()));
  }

  @DeleteMapping("/elevations/current")
  public ResponseEntity<ElevationService.Ended> endElevation(AuthContext caller) {
    return MfaController.noStore(elevations.end(caller));
  }

  public record AdminMe(UUID adminId, Set<String> roles, Set<String> permissions, boolean elevated,
      String tokenExpiresAt) {}

  public record AdminTokens(String accessToken, String tokenType, long expiresIn, String refreshToken,
      String refreshExpiresAt, UUID sessionId, List<String> roles, String elevatedUntil) {
    static AdminTokens of(AdminAuthService.Issued i) {
      return new AdminTokens(i.accessToken(), "Bearer", i.expiresIn(), i.refreshToken(),
          i.refreshExpiresAt().toString(), i.sessionId(), i.roles(),
          i.elevatedUntil() == null ? null : i.elevatedUntil().toString());
    }
  }
}
