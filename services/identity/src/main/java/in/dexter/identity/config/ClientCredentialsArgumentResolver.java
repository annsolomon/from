package in.dexter.identity.config;

import in.dexter.identity.app.ServiceTokenService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * HTTP Basic client authentication for {@code POST /oauth2/token} (RFC 6749 2.3.1: id and secret are
 * form-urlencoded, then Basic-encoded — what dexter-security's ServiceTokenClient sends). Resolves to null when the
 * header is missing or malformed; the use-case then answers 401 INVALID_CLIENT.
 */
final class ClientCredentialsArgumentResolver implements HandlerMethodArgumentResolver {
  private static final int MAX_HEADER = 1024;

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.getParameterType() == ServiceTokenService.Credentials.class;
  }

  @Override
  public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
      WebDataBinderFactory binderFactory) {
    return parse(request.getHeader(HttpHeaders.AUTHORIZATION));
  }

  static ServiceTokenService.Credentials parse(String header) {
    if (header == null || header.length() > MAX_HEADER || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
      return null;
    }
    try {
      String decoded = new String(Base64.getDecoder().decode(header.substring(6).strip()), StandardCharsets.UTF_8);
      int colon = decoded.indexOf(':');
      if (colon <= 0) {
        return null;
      }
      return new ServiceTokenService.Credentials(URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8),
          URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
