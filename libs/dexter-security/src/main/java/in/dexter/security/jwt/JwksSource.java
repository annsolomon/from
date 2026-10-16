package in.dexter.security.jwt;

import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;

/** Where the public keys come from: identity's {@code /.well-known/jwks.json}, or an in-memory set in tests. */
@FunctionalInterface
public interface JwksSource {
  JWKSet fetch() throws IOException;
}
