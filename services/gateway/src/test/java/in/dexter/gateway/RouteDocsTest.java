package in.dexter.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.gateway.domain.RouteClass;
import in.dexter.gateway.domain.RouteTable;
import in.dexter.gateway.domain.ServiceRoute;
import in.dexter.gateway.infra.RouteMatcher;
import in.dexter.gateway.support.RoutesDoc;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** docs/api/routes.md and the code's route table are the same thing (MASTER_PLAN P06 DoD). */
class RouteDocsTest {
  final List<RoutesDoc.Row> rows = RoutesDoc.rows();
  final RouteMatcher matcher = RouteMatcher.standard(true);

  @Test
  void everyPatternIsDocumentedAndNothingElse() {
    Set<String> code = new LinkedHashSet<>();
    RouteTable.ROUTES.forEach(r -> code.addAll(r.patterns()));
    code.addAll(RouteTable.DEV_IDENTITY.patterns());
    Set<String> doc = new LinkedHashSet<>();
    rows.forEach(r -> doc.add(r.pattern()));
    assertThat(doc).containsExactlyInAnyOrderElementsOf(code);
  }

  @Test
  void everyExampleMatchesItsDocumentedPatternServiceAccessAndClass() {
    assertThat(rows).hasSizeGreaterThan(80);
    for (RoutesDoc.Row r : rows) {
      String path = r.path().contains("?") ? r.path().substring(0, r.path().indexOf('?')) : r.path();
      RouteMatcher.Match m = matcher.match(r.method(), path);
      assertThat(m).as(r.toString()).isNotNull();
      assertThat(m.pattern()).as(r + " first matching pattern").isEqualTo(r.pattern());
      assertThat(m.route().service()).as(r.toString()).isEqualTo(r.service());
      assertThat(m.publicRoute()).as(r + " access").isEqualTo(r.publicAccess());
      assertThat(m.routeClass().id()).as(r + " class").isEqualTo(r.routeClass());
    }
  }

  @Test
  void everyServiceHasARouteAndOnlySseRoutesSkipTheBreaker() {
    Set<String> services = new LinkedHashSet<>();
    for (ServiceRoute r : RouteTable.ROUTES) {
      services.add(r.service());
      assertThat(r.sse()).as(r.id()).isEqualTo(r.id().equals("reco-stream"));
    }
    assertThat(services).containsExactlyInAnyOrderElementsOf(RouteTable.SERVICES);
  }

  @Test
  void unknownAndInternalPathsHaveNoRoute() {
    for (String p : List.of("/v1/nope", "/oauth2/token", "/internal/v1/x", "/actuator/health", "/v2/expenses",
        "/v1/expensesx", "/v1/admin/unknown", "/_dev/p02/status")) {
      assertThat(matcher.match("GET", p)).as(p).isNull();
    }
    assertThat(RouteMatcher.standard(false).match("POST", "/_dev/p04/keys/rotate")).isNull();
  }

  @Test
  void routeClasses() {
    assertThat(RouteClass.of("POST", "/v1/auth/login")).isEqualTo(RouteClass.AUTH);
    assertThat(RouteClass.of("POST", "/v1/admin/auth/login")).isEqualTo(RouteClass.AUTH);
    assertThat(RouteClass.of("POST", "/v1/calc/emi")).isEqualTo(RouteClass.CALC);
    assertThat(RouteClass.of("GET", "/v1/expenses")).isEqualTo(RouteClass.READ);
    assertThat(RouteClass.of("DELETE", "/v1/expenses/1")).isEqualTo(RouteClass.WRITE);
    assertThat(RouteClass.AUTH.defaultPerMinute()).isEqualTo(10);
    assertThat(RouteClass.WRITE.defaultPerMinute()).isEqualTo(120);
    assertThat(RouteClass.READ.defaultPerMinute()).isEqualTo(600);
    assertThat(RouteClass.CALC.defaultPerMinute()).isEqualTo(60);
  }
}
