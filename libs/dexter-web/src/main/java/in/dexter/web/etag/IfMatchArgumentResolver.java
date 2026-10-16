package in.dexter.web.etag;

import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Resolves {@link IfMatchVersion} parameters. */
public class IfMatchArgumentResolver implements HandlerMethodArgumentResolver {
  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(IfMatchVersion.class)
        && (parameter.getParameterType() == long.class || parameter.getParameterType() == Long.class);
  }

  @Override
  public Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mav,
      NativeWebRequest request, @Nullable WebDataBinderFactory binderFactory) {
    return ETags.parseIfMatch(request.getHeader(HttpHeaders.IF_MATCH));
  }
}
