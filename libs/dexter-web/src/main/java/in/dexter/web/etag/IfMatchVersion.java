package in.dexter.web.etag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the version from a required {@code If-Match: "<version>"} header to a {@code long}
 * parameter (MASTER_PLAN 7.3, 9). Missing → 428 IF_MATCH_REQUIRED; weak/malformed → 400
 * IF_MATCH_INVALID. A stale version is detected by the versioned UPDATE → 412 VERSION_CONFLICT.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface IfMatchVersion {}
