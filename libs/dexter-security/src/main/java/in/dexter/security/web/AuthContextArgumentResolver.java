package in.dexter.security.web;

import in.dexter.core.error.UnauthenticatedException;
import in.dexter.core.tenant.Tenant;
import in.dexter.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Controller parameters of type {@link AuthContext} or {@link Tenant} (the caller acting as the
 * account holder, role USER). This is the only way business code gets an account id when acting as
 * the user (MASTER_PLAN 7.2).
 */
public class AuthContextArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    Class<?> t = parameter.getParameterType();
    return t == AuthContext.class || t == Tenant.class;
  }

  @Override
  public Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mav,
      NativeWebRequest request, @Nullable WebDataBinderFactory binderFactory) {
    HttpServletRequest http = request.getNativeRequest(HttpServletRequest.class);
    AuthContext ctx = http == null ? null : SecurityInterceptor.current(http);
    if (ctx == null) {
      // A @PublicEndpoint (or public path) handler asked for the caller: a programming error, fail closed.
      throw new UnauthenticatedException("This endpoint has no authenticated caller");
    }
    return parameter.getParameterType() == Tenant.class ? ctx.tenant() : ctx;
  }
}
