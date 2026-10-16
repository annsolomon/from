package in.dexter.testkit.archfixture.bad.api;

import in.dexter.cache.TenantCache;
import in.dexter.security.web.PublicEndpoint;
import in.dexter.security.web.RequiresPermission;
import in.dexter.testkit.archfixture.bad.infra.BadRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.RequestHeader;

/** ArchRules fixture: every way a controller can break the rules. Never instantiated. */
public class BadController {
  JdbcClient jdbc;          // JDBC in api
  TenantCache cache;        // cache in api
  BadRepository repository; // api -> infra

  public String layered() {
    return repository.toString();
  }

  @PublicEndpoint
  @RequiresPermission("notes:read")
  public String combined() {
    return "x";
  }

  public String spoofed(@RequestHeader("X-Account-Id") String account) {
    return account;
  }

  public String authorization(@RequestHeader(name = "Authorization") String token) {
    return token;
  }

  public String allHeaders(@RequestHeader Map<String, String> headers) {
    return headers.toString();
  }

  public String fine(@RequestHeader("X-Request-Id") String requestId) {
    return requestId;
  }

  public String raw(HttpServletRequest request) {
    return request.getHeader("X-Tenant");
  }
}
