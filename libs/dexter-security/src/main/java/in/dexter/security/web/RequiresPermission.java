package in.dexter.security.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The caller must hold <b>every</b> listed permission ({@code perms} claim, MASTER_PLAN 7.2).
 * Class and method annotations add up. Missing → 403 FORBIDDEN. Admin DB policies still apply
 * (defense in depth).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresPermission {
  String[] value();
}
