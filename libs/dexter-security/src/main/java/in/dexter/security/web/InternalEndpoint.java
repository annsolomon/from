package in.dexter.security.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Service-to-service endpoint: only client-credentials tokens ({@code sub = svc:<name>}) are
 * accepted, and when {@link #callers()} is not empty only those services. User and admin tokens get
 * 403. Conversely, handlers without this annotation reject service tokens.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface InternalEndpoint {
  /** Allowed calling services (empty = any Dexter service). */
  String[] callers() default {};
}
