package in.dexter.security.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sensitive action (MASTER_PLAN 7.1): {@code auth_time} must be within {@code dexter.security.step-up-max-age}
 * (10 min) and, when the account has MFA enrolled, {@code amr} must contain {@code mfa}. Otherwise
 * 401 {@code STEP_UP_REQUIRED} with an RFC 9470 {@code WWW-Authenticate} challenge.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresStepUp {}
