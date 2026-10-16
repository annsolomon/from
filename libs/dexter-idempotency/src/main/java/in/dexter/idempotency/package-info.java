/**
 * Idempotency-Key engine (MASTER_PLAN 5). {@link in.dexter.idempotency.IdempotencyEngine} runs inside
 * {@code TenantTx.write}; controllers take the key through
 * {@link in.dexter.idempotency.web.IdempotencyKey} and answer with
 * {@link in.dexter.idempotency.web.IdempotentResponses}.
 */
package in.dexter.idempotency;
