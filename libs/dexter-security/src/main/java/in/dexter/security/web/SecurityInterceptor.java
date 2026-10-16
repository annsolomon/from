package in.dexter.security.web;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.ForbiddenException;
import in.dexter.core.error.UnauthenticatedException;
import in.dexter.security.AuthContext;
import in.dexter.security.SecurityErrors;
import in.dexter.security.jwt.JwtVerifier;
import in.dexter.security.jwt.TokenRejectedException;
import in.dexter.web.error.ProblemDetailsAdvice;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.PathContainer;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Default-deny authentication and authorization for Spring MVC handlers (MASTER_PLAN 7.2, ADR-0007).
 *
 * <p>Runs inside the DispatcherServlet, so its exceptions become coded ProblemDetails through
 * {@link ProblemDetailsAdvice} like any other error. For every handler method:
 * <ol>
 *   <li>{@link PublicEndpoint}, or a configured public path on a handler without security
 *       annotations → no authentication.</li>
 *   <li>Otherwise a Bearer access token is required and verified ({@link JwtVerifier}); the
 *       {@link AuthContext} is stored as a request attribute and the caller's id goes into the MDC.</li>
 *   <li>Caller kind: {@link InternalEndpoint} handlers accept only service tokens (optionally only
 *       from {@code callers}); every other handler rejects service tokens.</li>
 *   <li>{@link RequiresPermission}: all listed permissions (class + method).</li>
 *   <li>{@link RequiresStepUp}: recent {@code auth_time}, plus {@code amr=mfa} when MFA is enrolled.</li>
 * </ol>
 * Identity headers from clients ({@code X-Account-*}, {@code X-Actor-*}) are never read.
 */
public class SecurityInterceptor implements HandlerInterceptor {
  public static final String AUTH_CONTEXT_ATTRIBUTE = AuthContext.class.getName();
  public static final String MDC_PRINCIPAL = "principal";
  static final String REALM = "dexter";
  private static final Logger log = LoggerFactory.getLogger(SecurityInterceptor.class);

  private final JwtVerifier verifier;
  private final Clock clock;
  private final Duration stepUpMaxAge;
  private final List<PathPattern> publicPaths;
  private final Map<Method, Policy> policies = new ConcurrentHashMap<>();

  public SecurityInterceptor(JwtVerifier verifier, Clock clock, Duration stepUpMaxAge, List<String> publicPaths) {
    this.verifier = Objects.requireNonNull(verifier, "verifier");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.stepUpMaxAge = Objects.requireNonNull(stepUpMaxAge, "stepUpMaxAge");
    this.publicPaths = publicPaths.stream().map(PathPatternParser.defaultInstance::parse).toList();
  }

  /** What a handler requires; computed once per handler method. */
  record Policy(boolean publicEndpoint, @Nullable Set<String> internalCallers, Set<String> permissions, boolean stepUp) {
    boolean hasRequirements() {
      return internalCallers != null || !permissions.isEmpty() || stepUp;
    }

    static Policy of(HandlerMethod hm) {
      Method m = hm.getMethod();
      Class<?> type = hm.getBeanType();
      boolean pub = has(m, type, PublicEndpoint.class);
      InternalEndpoint internal = AnnotatedElementUtils.findMergedAnnotation(m, InternalEndpoint.class);
      if (internal == null) {
        internal = AnnotatedElementUtils.findMergedAnnotation(type, InternalEndpoint.class);
      }
      Set<String> perms = new LinkedHashSet<>();
      RequiresPermission onType = AnnotatedElementUtils.findMergedAnnotation(type, RequiresPermission.class);
      RequiresPermission onMethod = AnnotatedElementUtils.findMergedAnnotation(m, RequiresPermission.class);
      if (onType != null) {
        perms.addAll(Arrays.asList(onType.value()));
      }
      if (onMethod != null) {
        perms.addAll(Arrays.asList(onMethod.value()));
      }
      boolean stepUp = has(m, type, RequiresStepUp.class);
      Policy p = new Policy(pub, internal == null ? null : Set.of(internal.callers()), Set.copyOf(perms), stepUp);
      if (pub && p.hasRequirements()) {
        throw new IllegalStateException("@PublicEndpoint cannot be combined with security requirements: " + m);
      }
      return p;
    }

    private static boolean has(Method m, Class<?> type, Class<? extends java.lang.annotation.Annotation> a) {
      return AnnotatedElementUtils.hasAnnotation(m, a) || AnnotatedElementUtils.hasAnnotation(type, a);
    }
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod hm)) {
      return true;
    }
    Policy policy = policies.computeIfAbsent(hm.getMethod(), m -> Policy.of(hm));
    if (policy.publicEndpoint() || (!policy.hasRequirements() && isPublicPath(request))) {
      return true;
    }
    AuthContext ctx = authenticate(request, response);
    request.setAttribute(AUTH_CONTEXT_ATTRIBUTE, ctx);
    MDC.put(MDC_PRINCIPAL, ctx.accountId() != null ? ctx.accountId().toString() : "svc:" + ctx.service());
    authorize(policy, ctx, response);
    return true;
  }

  @Override
  public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
      @Nullable Exception ex) {
    MDC.remove(MDC_PRINCIPAL);
  }

  /** The verified caller of this request, or null when the handler is public. */
  public static @Nullable AuthContext current(HttpServletRequest request) {
    return request.getAttribute(AUTH_CONTEXT_ATTRIBUTE) instanceof AuthContext a ? a : null;
  }

  private AuthContext authenticate(HttpServletRequest request, HttpServletResponse response) {
    String token = BearerTokens.extract(request.getHeader(BearerTokens.HEADER));
    if (token == null) {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"" + REALM + "\"");
      throw new UnauthenticatedException("A Bearer access token is required");
    }
    try {
      return verifier.verify(token);
    } catch (TokenRejectedException e) {
      if (e.reason() == TokenRejectedException.Reason.JWKS_UNAVAILABLE) {
        log.error("AUTH_JWKS_UNAVAILABLE {}", e.getMessage());
        throw new DexterException(CoreErrors.SERVICE_UNAVAILABLE, "Authentication is temporarily unavailable",
            List.of(), Map.of(ProblemDetailsAdvice.RETRY_AFTER_PROPERTY, 5), null);
      }
      log.info("AUTH_REJECTED reason={} path={}", e.reason(), request.getRequestURI());
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"" + REALM + "\", error=\"invalid_token\"");
      throw new UnauthenticatedException("The access token is invalid or expired");
    }
  }

  private void authorize(Policy policy, AuthContext ctx, HttpServletResponse response) {
    Set<String> callers = policy.internalCallers();
    if (callers != null) {
      if (!ctx.isService()) {
        throw new ForbiddenException("This endpoint accepts service tokens only");
      }
      if (!callers.isEmpty() && !callers.contains(ctx.service())) {
        throw new ForbiddenException("This service may not call this endpoint");
      }
    } else if (ctx.isService()) {
      throw new ForbiddenException("Service tokens are not accepted on this endpoint");
    }
    if (!policy.permissions().isEmpty()) {
      Set<String> missing = new TreeSet<>(policy.permissions());
      missing.removeAll(ctx.permissions());
      if (!missing.isEmpty()) {
        throw new DexterException(CoreErrors.FORBIDDEN, "Missing permission", List.of(),
            Map.of("missingPermissions", List.copyOf(missing)), null);
      }
    }
    if (policy.stepUp()) {
      stepUp(ctx, response);
    }
  }

  private void stepUp(AuthContext ctx, HttpServletResponse response) {
    Instant now = clock.instant();
    Instant authTime = ctx.authTime();
    boolean recent = authTime != null && !authTime.isBefore(now.minus(stepUpMaxAge))
        && !authTime.isAfter(now.plus(verifier.settings().clockSkew()));
    boolean mfaOk = !ctx.mfaEnrolled() || ctx.amr().contains(AuthContext.AMR_MFA);
    if (recent && mfaOk) {
      return;
    }
    long maxAge = stepUpMaxAge.toSeconds();
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"" + REALM
        + "\", error=\"insufficient_user_authentication\", error_description=\"Recent authentication required\", max_age="
        + maxAge);
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("maxAgeSeconds", maxAge);
    props.put("mfaRequired", ctx.mfaEnrolled());
    throw new DexterException(SecurityErrors.STEP_UP_REQUIRED,
        ctx.mfaEnrolled() ? "Sign in again with your second factor to continue" : "Sign in again to continue",
        List.of(), props, null);
  }

  private boolean isPublicPath(HttpServletRequest request) {
    if (publicPaths.isEmpty()) {
      return false;
    }
    String uri = request.getRequestURI();
    String ctxPath = request.getContextPath();
    String path = ctxPath != null && !ctxPath.isEmpty() && uri.startsWith(ctxPath) ? uri.substring(ctxPath.length()) : uri;
    PathContainer container = PathContainer.parsePath(path);
    for (PathPattern p : publicPaths) {
      if (p.matches(container)) {
        return true;
      }
    }
    return false;
  }
}
