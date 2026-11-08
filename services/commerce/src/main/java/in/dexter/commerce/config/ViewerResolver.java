package in.dexter.commerce.config;

import in.dexter.commerce.app.Viewer;
import in.dexter.commerce.domain.Hashing;
import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.UnauthenticatedException;
import in.dexter.security.AuthContext;
import in.dexter.security.jwt.JwtVerifier;
import in.dexter.security.jwt.TokenRejectedException;
import in.dexter.security.web.BearerTokens;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link Viewer} for commerce's public routes (MASTER_PLAN P17: the feed, a deal, an ad slot and the
 * redirect are public, and a signed-in viewer gets card-match and ad-free). {@code @PublicEndpoint} skips the
 * security interceptor, so the optional token is verified here with the same {@link JwtVerifier}: no header =
 * anonymous; a header that does not verify = 401 (a client with a stale token refreshes rather than silently
 * losing its personalisation); a verified non-user token (admin, service) = anonymous. The account comes only
 * from the verified token. The client address is the first X-Forwarded-For hop the gateway appends, else the
 * socket peer; it and the user agent are hashed with the salt before anything else sees them.
 */
final class ViewerResolver implements HandlerMethodArgumentResolver {
  private final JwtVerifier verifier;
  private final String salt;

  ViewerResolver(JwtVerifier verifier, String salt) {
    this.verifier = verifier;
    this.salt = salt;
  }

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.getParameterType() == Viewer.class;
  }

  @Override
  public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest web,
      WebDataBinderFactory binders) {
    HttpServletRequest request = web.getNativeRequest(HttpServletRequest.class);
    if (request == null) {
      throw new IllegalStateException("not a servlet request");
    }
    UUID account = null;
    String header = request.getHeader(BearerTokens.HEADER);
    if (header != null) {
      String token = BearerTokens.extract(header);
      if (token == null) {
        throw new UnauthenticatedException("Malformed Authorization header");
      }
      AuthContext ctx;
      try {
        ctx = verifier.verify(token);
      } catch (TokenRejectedException e) {
        if (e.reason() == TokenRejectedException.Reason.JWKS_UNAVAILABLE) {
          throw new DexterException(CoreErrors.SERVICE_UNAVAILABLE, "Authentication is temporarily unavailable",
              List.of(), java.util.Map.of(), null);
        }
        throw new UnauthenticatedException("The access token is invalid or expired");
      }
      if (ctx.kind() == AuthContext.Kind.USER) {
        account = ctx.accountId();
      }
    }
    String forwarded = request.getHeader("X-Forwarded-For");
    String ip = forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].strip();
    String ua = request.getHeader("User-Agent");
    return new Viewer(account, Hashing.salted(salt, "ip:" + ip), Hashing.salted(salt, "ua:" + (ua == null ? "" : ua)));
  }
}
