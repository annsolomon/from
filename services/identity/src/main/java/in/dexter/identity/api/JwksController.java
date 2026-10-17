package in.dexter.identity.api;

import in.dexter.identity.app.SigningKeyService;
import in.dexter.security.web.PublicEndpoint;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The public signing keys (next, active, retired) every service verifies tokens with (MASTER_PLAN 7.1). */
@RestController
public class JwksController {
  private final SigningKeyService keys;

  public JwksController(SigningKeyService keys) {
    this.keys = keys;
  }

  @PublicEndpoint
  @GetMapping("/.well-known/jwks.json")
  public ResponseEntity<String> jwks() {
    return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
        .contentType(MediaType.APPLICATION_JSON).body(keys.jwks());
  }
}
