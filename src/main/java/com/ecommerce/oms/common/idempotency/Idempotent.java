package com.ecommerce.oms.common.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as at-most-once per {@code Idempotency-Key}.
 *
 * <p>Declarative on purpose: idempotency is genuinely needed by exactly one endpoint
 * ({@code POST /checkout}) and it would be wasteful to pay for it on the other thirty.
 * {@link IdempotencyAspect} does the work, so the controller method stays free of any
 * replay bookkeeping.
 *
 * <p>Semantics enforced by the aspect:
 * <ul>
 *   <li>Missing or blank header → {@code 400 VALIDATION_FAILED}.</li>
 *   <li>Same key, same request body, first call completed → the stored response is
 *       replayed verbatim with the original status and an {@code Idempotent-Replay: true}
 *       header. No side effects run a second time.</li>
 *   <li>Same key, still in flight → {@code 409 REQUEST_IN_PROGRESS}.</li>
 *   <li>Same key, <em>different</em> body → {@code 422 IDEMPOTENCY_KEY_REUSED}. Silently
 *       replaying a different request's result would be worse than failing.</li>
 *   <li>First call threw → the record is removed so a genuine retry can proceed.</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** Header carrying the client-supplied key. */
    String header() default "Idempotency-Key";
}
