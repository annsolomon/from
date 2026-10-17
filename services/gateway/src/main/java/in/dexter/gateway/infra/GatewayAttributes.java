package in.dexter.gateway.infra;

/** Exchange attributes the gateway's filters share. */
public final class GatewayAttributes {
  public static final String REQUEST_ID = "dexter.requestId";
  public static final String CLIENT_IP = "dexter.clientIp";
  /** The verified caller ({@code in.dexter.security.AuthContext}), absent on public routes / anonymous calls. */
  public static final String AUTH = "dexter.auth";
  /** {@link RouteMatcher.Match} of this request, absent when no route matches. */
  public static final String ROUTE = "dexter.route";
  public static final String STARTED_NANOS = "dexter.started";

  private GatewayAttributes() {}
}
