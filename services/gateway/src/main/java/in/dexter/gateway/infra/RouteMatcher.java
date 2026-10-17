package in.dexter.gateway.infra;

import in.dexter.gateway.domain.PublicRoute;
import in.dexter.gateway.domain.RouteClass;
import in.dexter.gateway.domain.RouteTable;
import in.dexter.gateway.domain.ServiceRoute;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Which route (and route class) a request belongs to, and whether it is public — the same patterns the gateway
 * routes with, so the filters and the routing can never disagree.
 */
public final class RouteMatcher {
  /** A request's route: the service, the matched pattern (low-cardinality, for activity events) and its class. */
  public record Match(ServiceRoute route, String pattern, RouteClass routeClass, boolean publicRoute) {}

  private record Compiled(ServiceRoute route, PathPattern pattern) {}

  private record CompiledPublic(String method, PathPattern pattern) {}

  private final List<Compiled> routes = new ArrayList<>();
  private final List<CompiledPublic> publics = new ArrayList<>();

  public RouteMatcher(List<ServiceRoute> table, List<PublicRoute> publicRoutes) {
    PathPatternParser parser = PathPatternParser.defaultInstance;
    for (ServiceRoute r : table) {
      for (String p : r.patterns()) {
        routes.add(new Compiled(r, parser.parse(p)));
      }
    }
    for (PublicRoute p : publicRoutes) {
      publics.add(new CompiledPublic(p.method(), parser.parse(p.pattern())));
    }
  }

  public static RouteMatcher standard(boolean devRoutes) {
    List<ServiceRoute> table = new ArrayList<>(RouteTable.ROUTES);
    List<PublicRoute> pub = new ArrayList<>(RouteTable.PUBLIC);
    if (devRoutes) {
      table.add(RouteTable.DEV_IDENTITY);
      RouteTable.DEV_IDENTITY.patterns().forEach(p -> {
        pub.add(new PublicRoute("GET", p));
        pub.add(new PublicRoute("POST", p));
      });
    }
    return new RouteMatcher(table, pub);
  }

  /** The first route (table order) whose pattern matches, or null. */
  public Match match(String method, String path) {
    PathContainer pc = PathContainer.parsePath(path);
    for (Compiled c : routes) {
      if (c.pattern().matches(pc)) {
        return new Match(c.route(), c.pattern().getPatternString(), RouteClass.of(method, path), isPublic(method, pc));
      }
    }
    return null;
  }

  private boolean isPublic(String method, PathContainer pc) {
    for (CompiledPublic p : publics) {
      if (p.method().equals(method) && p.pattern().matches(pc)) {
        return true;
      }
    }
    return false;
  }
}
