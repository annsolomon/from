package in.dexter.web.page;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.db.page.PageRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves a {@link PageRequest} controller parameter from {@code ?limit=&cursor=} with the
 * MASTER_PLAN 4.1 rules: default 25, 1..100, anything else → 400 LIMIT_INVALID.
 */
public class PageRequestArgumentResolver implements HandlerMethodArgumentResolver {
  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.getParameterType() == PageRequest.class;
  }

  @Override
  public Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mav,
      NativeWebRequest request, @Nullable WebDataBinderFactory binderFactory) {
    String rawLimit = request.getParameter("limit");
    Integer limit = null;
    if (rawLimit != null) {
      if (!rawLimit.matches("-?[0-9]{1,6}")) {
        throw new DexterException(CoreErrors.LIMIT_INVALID, "limit must be between 1 and " + PageRequest.MAX_LIMIT);
      }
      limit = Integer.parseInt(rawLimit);
    }
    return PageRequest.of(limit, request.getParameter("cursor"));
  }
}
