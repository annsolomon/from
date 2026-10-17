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
}
