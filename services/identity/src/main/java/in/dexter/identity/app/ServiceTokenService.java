package in.dexter.identity.app;

import in.dexter.core.error.DexterException;
import in.dexter.identity.domain.IdentityErrors;
import in.dexter.identity.domain.SecretTokens;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POST /oauth2/token, client credentials (MASTER_PLAN 7.2, A-18): another service authenticates with HTTP Basic
 * and gets a 5-minute token for one audience. Secrets are compared as SHA-256 digests in constant time.
 */
public final class ServiceTokenService {
  private static final Logger log = LoggerFactory.getLogger(ServiceTokenService.class);
  public static final String GRANT = "client_credentials";

  /** Basic credentials as sent by the client. */
  public record Credentials(String clientId, String secret) {}

  private final AccessTokens tokens;
  private final IdentitySettings settings;

  public ServiceTokenService(AccessTokens tokens, IdentitySettings settings) {
    this.tokens = tokens;
    this.settings = settings;
  }

  /** RFC 6749 4.4 response body. */
  public Map<String, Object> issue(Credentials creds, String grantType, String audience) {
    IdentitySettings.ServiceClient client = creds == null ? null : settings.serviceClients().get(creds.clientId());
    byte[] presented = creds == null || creds.secret() == null ? new byte[32]
        : SecretTokens.sha256(creds.secret().getBytes(StandardCharsets.UTF_8));
    boolean ok = client != null && SecretTokens.equal(presented, client.secretSha256());
    if (!ok) {
      log.info("SERVICE_TOKEN_DENIED client={} reason=invalid_client", creds == null ? "-" : creds.clientId());
      throw new DexterException(IdentityErrors.INVALID_CLIENT, "Client authentication failed");
    }
    if (!GRANT.equals(grantType)) {
      throw new DexterException(IdentityErrors.UNSUPPORTED_GRANT_TYPE, "Only client_credentials is supported");
    }
    if (audience == null || !client.audiences().contains(audience)) {
      log.info("SERVICE_TOKEN_DENIED client={} audience={} reason=invalid_target", client.id(), audience);
      throw new DexterException(IdentityErrors.INVALID_AUDIENCE, "This client may not call " + audience);
    }
    AccessTokens.Issued t = tokens.forService(client.id(), audience);
    return Map.of("access_token", t.token(), "token_type", "Bearer", "expires_in", t.expiresIn());
  }
}
