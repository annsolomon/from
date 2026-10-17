package in.dexter.identity.app;

/** POST /v1/auth/login body. */
public record LoginCommand(String email, String password, String clientId, String deviceLabel) {}
