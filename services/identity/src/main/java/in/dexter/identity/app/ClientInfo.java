package in.dexter.identity.app;

/** Where a sign-in came from (stored on the session; the user agent only as a hash). */
public record ClientInfo(String ip, String userAgent) {}
