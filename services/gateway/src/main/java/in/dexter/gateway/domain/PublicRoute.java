package in.dexter.gateway.domain;

/** A method + path pattern the gateway lets through without a token (the service still decides). */
public record PublicRoute(String method, String pattern) {}
