package in.dexter.identity.api;

import in.dexter.identity.app.ServiceTokenService;
import in.dexter.security.web.PublicEndpoint;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Client-credentials token endpoint for service-to-service calls (MASTER_PLAN 7.2, A-18). Public at the user-token
 * level: the client authenticates with HTTP Basic, resolved into {@link ServiceTokenService.Credentials} by config
 * (the only place outside dexter-security that reads an Authorization header).
 */
@RestController
public class OAuthTokenController {
  private final ServiceTokenService tokens;

  public OAuthTokenController(ServiceTokenService tokens) {
    this.tokens = tokens;
  }

  @PublicEndpoint
  @PostMapping(value = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  public ResponseEntity<Map<String, Object>> token(ServiceTokenService.Credentials client,
      @RequestParam(name = "grant_type", required = false) String grantType,
      @RequestParam(name = "audience", required = false) String audience) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
        .body(tokens.issue(client, grantType, audience));
  }
}
