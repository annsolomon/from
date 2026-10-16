package in.dexter.idempotency.web;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.id.Ids;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Resolves {@link IdempotencyKey} parameters. */
public class IdempotencyKeyArgumentResolver implements HandlerMethodArgumentResolver {
  public static final String HEADER = "Idempotency-Key";

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(IdempotencyKey.class) && parameter.getParameterType() == UUID.class;
  }

  @Override
  public @Nullable Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mav,
      NativeWebRequest request, @Nullable WebDataBinderFactory binderFactory) {
    IdempotencyKey ann = parameter.getParameterAnnotation(IdempotencyKey.class);
    return parse(request.getHeader(HEADER), ann != null && ann.required());
  }

  /** Header value → key (also used by filters and tests). */
  public static @Nullable UUID parse(@Nullable String raw, boolean required) {
    if (raw == null || raw.isBlank()) {
      if (required) {
        throw new DexterException(CoreErrors.IDEMPOTENCY_KEY_REQUIRED,
            "This endpoint requires an Idempotency-Key header (a UUID)");
      }
      return null;
    }
    return Ids.parse(raw.strip().toLowerCase(java.util.Locale.ROOT), HEADER);
  }
}
