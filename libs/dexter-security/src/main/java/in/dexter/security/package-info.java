/**
 * Zero-trust request authentication (MASTER_PLAN 7.2, ADR-0007). Every service validates the access
 * token itself with {@link in.dexter.security.jwt.JwtVerifier}; the result is an
 * {@link in.dexter.security.AuthContext}, which feeds {@link in.dexter.core.tenant.Tenant} for RLS.
 * Servlet services get {@link in.dexter.security.web.SecurityInterceptor} (default deny) and the
 * {@code AuthContext}/{@code Tenant} controller arguments through auto-configuration.
 */
package in.dexter.security;
