package in.dexter.idempotency.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the {@code Idempotency-Key} header (MASTER_PLAN 5.1) to a {@link java.util.UUID} parameter.
 * Missing and {@link #required()} → 400 IDEMPOTENCY_KEY_REQUIRED; missing and optional → null (the
 * request runs normally); not a canonical UUID → 400 INVALID_ID.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotencyKey {
  /** Money-related and creating POSTs set this (listed per phase). */
  boolean required() default false;
}
