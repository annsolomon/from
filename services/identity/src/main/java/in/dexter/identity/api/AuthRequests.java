package in.dexter.identity.api;

import in.dexter.identity.app.LoginCommand;
import in.dexter.identity.app.RegisterCommand;

/** Request bodies of /v1/auth (strict JSON: unknown or duplicate fields are 400). */
public final class AuthRequests {
  private AuthRequests() {}

  public record Register(String email, String password, String displayName, String tz, String clientId) {
    RegisterCommand command() {
      return new RegisterCommand(email, password, displayName, tz, clientId);
    }
  }

  public record Login(String email, String password, String clientId, String deviceLabel) {
    LoginCommand command() {
      return new LoginCommand(email, password, clientId, deviceLabel);
    }
  }

  public record Token(String token) {}

  public record Refresh(String refreshToken) {}

  public record Forgot(String email) {}

  public record Reset(String token, String newPassword) {}

  public record Change(String currentPassword, String newPassword) {}

  public record MfaVerify(String mfaToken, String code, String recoveryCode) {}

  public record StepUp(String password, String code, String recoveryCode) {}

  public record MfaCode(String code) {}

  public record AdminLogin(String email, String password) {}

  public record AdminMfa(String mfaToken, String code) {}

  public record InviteAccept(String token, String password) {}

  public record Elevate(String reason, String code) {}

  public record NewAdmin(String email, String displayName, java.util.List<String> roles, Boolean breakGlassEligible) {}
}
