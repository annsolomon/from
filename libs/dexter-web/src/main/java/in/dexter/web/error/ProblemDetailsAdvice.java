package in.dexter.web.error;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.ErrorCode;
import in.dexter.core.error.FieldError;
import in.dexter.web.request.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Every error becomes an RFC 9457 ProblemDetail with a stable {@code code} and the
 * {@code requestId} (MASTER_PLAN 9). Spring MVC's own exceptions get codes too. Unexpected
 * exceptions are logged with the request id and answered 500 INTERNAL_ERROR with no internals.
 * Lowest precedence so a service can add narrower advice.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {
  public static final String TYPE_BASE = "https://docs.dexter.in/problems/";
  /** A {@code DexterException} property that also becomes a {@code Retry-After} header (seconds). */
  public static final String RETRY_AFTER_PROPERTY = "retryAfterSeconds";
  private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);
  private static final Pattern DUP = Pattern.compile("Duplicate Object property \"([A-Za-z0-9_]{1,64})\"");

  @ExceptionHandler(DexterException.class)
  public ResponseEntity<Object> handleDexter(DexterException ex, WebRequest request) {
    ErrorCode code = ex.errorCode();
    if (code.status() >= 500) {
      log.error("{} {}", code.code(), ex.getMessage(), ex);
    }
    ProblemDetail pd = problem(code, ex.detail(), ex.fieldErrors(), request);
    ex.properties().forEach(pd::setProperty);
    ResponseEntity.BodyBuilder res = ResponseEntity.status(code.status());
    // P02: 409 IDEMPOTENCY_IN_PROGRESS (and later 429/503) tell clients when to retry.
    if (ex.properties().get(RETRY_AFTER_PROPERTY) instanceof Number n && n.longValue() > 0) {
      res.header(HttpHeaders.RETRY_AFTER, Long.toString(n.longValue()));
    }
    return res.body(pd);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
    log.error("Unhandled exception", ex);
    return ResponseEntity.status(500)
        .body(problem(CoreErrors.INTERNAL_ERROR, "Something went wrong; quote the requestId to support", List.of(), request));
  }

  @Override
  protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
      HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
    List<FieldError> errors = new ArrayList<>();
    ErrorCode code = codeFor(ex, statusCode, errors);
    String detail = detailFor(ex, code, errors);
    if (statusCode.is5xxServerError()) {
      log.error("{} {}", code.code(), ex.getMessage(), ex);
    }
    return super.handleExceptionInternal(ex, problem(code, detail, errors, request), headers, statusCode, request);
  }

  static ErrorCode codeFor(Exception ex, HttpStatusCode status, List<FieldError> errors) {
    return switch (ex) {
      case HttpMessageNotReadableException e -> jsonCode(e, errors);
      case MethodArgumentNotValidException e -> {
        e.getBindingResult().getFieldErrors().forEach(fe -> errors.add(
            new FieldError(fe.getField(), upperSnake(fe.getCode()), fe.getDefaultMessage())));
        yield CoreErrors.VALIDATION_FAILED;
      }
      case HandlerMethodValidationException e -> {
        e.getParameterValidationResults().forEach(r -> r.getResolvableErrors().forEach(err -> errors.add(
            new FieldError(String.valueOf(r.getMethodParameter().getParameterName()), "INVALID", err.getDefaultMessage()))));
        yield CoreErrors.VALIDATION_FAILED;
      }
      case MissingServletRequestParameterException e -> {
        errors.add(new FieldError(e.getParameterName(), "REQUIRED", e.getParameterName() + " is required"));
        yield CoreErrors.VALIDATION_FAILED;
      }
      case TypeMismatchException e -> {
        errors.add(new FieldError(String.valueOf(e.getPropertyName()), "TYPE_MISMATCH", "has the wrong type"));
        yield CoreErrors.VALIDATION_FAILED;
      }
      case ServletRequestBindingException e -> CoreErrors.BAD_REQUEST;
      case NoResourceFoundException e -> CoreErrors.NOT_FOUND;
      case NoHandlerFoundException e -> CoreErrors.NOT_FOUND;
      case HttpRequestMethodNotSupportedException e -> CoreErrors.METHOD_NOT_ALLOWED;
      case HttpMediaTypeNotSupportedException e -> CoreErrors.UNSUPPORTED_MEDIA_TYPE;
      case HttpMediaTypeNotAcceptableException e -> CoreErrors.NOT_ACCEPTABLE;
      case MaxUploadSizeExceededException e -> CoreErrors.PAYLOAD_TOO_LARGE;
      default -> byStatus(status.value());
    };
  }

  private static ErrorCode jsonCode(HttpMessageNotReadableException e, List<FieldError> errors) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof UnrecognizedPropertyException u) {
        String name = safeName(u.getPropertyName());
        errors.add(new FieldError(name, "UNKNOWN_FIELD", "unknown field"));
        return CoreErrors.UNKNOWN_FIELD;
      }
      if (t instanceof StreamReadException s && s.getOriginalMessage() != null) {
        Matcher m = DUP.matcher(s.getOriginalMessage());
        if (m.find()) {
          errors.add(new FieldError(m.group(1), "DUPLICATE_FIELD", "field appears more than once"));
          return CoreErrors.DUPLICATE_FIELD;
        }
        if (s.getOriginalMessage().startsWith("Duplicate Object property")) {
          return CoreErrors.DUPLICATE_FIELD;
        }
      }
    }
    return CoreErrors.MALFORMED_JSON;
  }

  private static String detailFor(Exception ex, ErrorCode code, List<FieldError> errors) {
    return switch (code) {
      case CoreErrors.UNKNOWN_FIELD -> "Request body has a field that is not allowed";
      case CoreErrors.DUPLICATE_FIELD -> "Request body has a duplicate field";
      case CoreErrors.MALFORMED_JSON -> "Request body is missing or is not valid JSON for this endpoint";
      case CoreErrors.VALIDATION_FAILED -> "One or more fields are invalid";
      case CoreErrors.NOT_FOUND -> "No such resource";
      case CoreErrors.METHOD_NOT_ALLOWED -> "Method not allowed on this resource";
      default -> code.status() >= 500 ? "Something went wrong; quote the requestId to support" : code.title();
    };
  }

  static ErrorCode byStatus(int status) {
    return switch (status) {
      case 400 -> CoreErrors.BAD_REQUEST;
      case 401 -> CoreErrors.UNAUTHENTICATED;
      case 403 -> CoreErrors.FORBIDDEN;
      case 404 -> CoreErrors.NOT_FOUND;
      case 405 -> CoreErrors.METHOD_NOT_ALLOWED;
      case 406 -> CoreErrors.NOT_ACCEPTABLE;
      case 409 -> CoreErrors.CONFLICT;
      case 412 -> CoreErrors.VERSION_CONFLICT;
      case 413 -> CoreErrors.PAYLOAD_TOO_LARGE;
      case 415 -> CoreErrors.UNSUPPORTED_MEDIA_TYPE;
      case 428 -> CoreErrors.IF_MATCH_REQUIRED;
      case 429 -> CoreErrors.RATE_LIMITED;
      case 503 -> CoreErrors.SERVICE_UNAVAILABLE;
      default -> status >= 500 ? CoreErrors.INTERNAL_ERROR : CoreErrors.BAD_REQUEST;
    };
  }

  /** Builds the body. Also used by filters that answer before MVC (P06 gateway, rate limits). */
  public static ProblemDetail problem(ErrorCode code, String detail, List<FieldError> errors, WebRequest request) {
    HttpServletRequest http = request instanceof ServletWebRequest s ? s.getRequest() : null;
    return problem(code, detail, errors, http);
  }

  public static ProblemDetail problem(ErrorCode code, String detail, List<FieldError> errors, HttpServletRequest http) {
    ProblemDetail pd = ProblemDetail.forStatus(code.status());
    pd.setType(URI.create(TYPE_BASE + code.code().toLowerCase(Locale.ROOT).replace('_', '-')));
    pd.setTitle(code.title());
    pd.setDetail(detail);
    if (http != null) {
      try {
        pd.setInstance(URI.create(http.getRequestURI()));
      } catch (IllegalArgumentException ignored) {
        // unparseable path: leave instance out
      }
    }
    pd.setProperty("code", code.code());
    pd.setProperty("requestId", RequestIdFilter.current(http));
    if (errors != null && !errors.isEmpty()) {
      List<Map<String, String>> list = new ArrayList<>();
      for (FieldError fe : errors) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("field", fe.field());
        m.put("code", fe.code());
        m.put("message", fe.message());
        list.add(m);
      }
      pd.setProperty("errors", list);
    }
    return pd;
  }

  private static String safeName(String s) {
    return s != null && s.matches("[A-Za-z0-9_]{1,64}") ? s : "?";
  }

  private static String upperSnake(String s) {
    if (s == null) {
      return "INVALID";
    }
    return s.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
  }
}
