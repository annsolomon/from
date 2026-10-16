package in.dexter.web.request;

import in.dexter.core.request.RequestIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * X-Request-Id (MASTER_PLAN 9): accepts a safe client value or generates a UUIDv7, echoes it on the
 * response, exposes it as a request attribute and puts it in the MDC for every log line of the
 * request. Registered first in the filter chain.
 */
public class RequestIdFilter extends OncePerRequestFilter {
  public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = RequestIds.acceptOrGenerate(request.getHeader(RequestIds.HEADER));
    request.setAttribute(ATTRIBUTE, id);
    response.setHeader(RequestIds.HEADER, id);
    MDC.put(RequestIds.MDC_KEY, id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(RequestIds.MDC_KEY);
    }
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  /** The id of the current request, or null outside a request. */
  public static String current(HttpServletRequest request) {
    Object v = request == null ? null : request.getAttribute(ATTRIBUTE);
    return v instanceof String s ? s : MDC.get(RequestIds.MDC_KEY);
  }
}
