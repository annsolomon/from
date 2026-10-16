package in.dexter.security.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts a handler (method or controller) out of authentication. Every other handler requires a valid
 * access token (default deny). Use sparingly: login, registration, JWKS, webhooks with their own
 * signature check.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PublicEndpoint {}
