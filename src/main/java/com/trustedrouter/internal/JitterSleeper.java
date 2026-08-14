package com.trustedrouter.internal;

import com.trustedrouter.errors.InternalException;
import java.io.InterruptedIOException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Jittered exponential backoff with the retry-after floor. Internal class
 * with no compatibility guarantees.
 *
 * <p>The ceiling doubles from {@link RetryPolicy#BASE_BACKOFF_MILLIS} up to
 * {@link RetryPolicy#MAX_BACKOFF_MILLIS} with the exponent capped at
 * {@link RetryPolicy#MAX_BACKOFF_EXPONENT}; the actual delay is uniform
 * jitter under that ceiling. A server-requested retry-after is honoured as a
 * FLOOR — {@code max}, never a replacement — so a 10ms retry-after-ms still
 * beats a 30s Retry-After
 * ({@code ShouldRetryHeaderTest.retryAfterMsIsHonoredAndBeatsRetryAfter}).
 */
public final class JitterSleeper implements Sleeper {
    /** Computes the jittered delay without sleeping; exposed for tests. */
    public long delayMillis(int attempt, Double retryAfterSeconds) {
        int bounded = Math.min(RetryPolicy.MAX_BACKOFF_EXPONENT, Math.max(0, attempt));
        long ceiling = Math.min(
                RetryPolicy.MAX_BACKOFF_MILLIS, RetryPolicy.BASE_BACKOFF_MILLIS * (1L << bounded));
        long delay = ceiling == 0L ? 0L : ThreadLocalRandom.current().nextLong(ceiling + 1L);
        if (retryAfterSeconds != null) {
            // Re-clamp rather than trusting the caller: delayMillis is public
            // and reachable independently of ErrorClassifier, and the
            // double->long narrowing below SATURATES per JLS 5.1.3 rather than
            // throwing, so an unbounded hint lands as Long.MAX_VALUE
            // milliseconds — roughly 292 million years — with nothing to notice.
            Double boundedHint = ErrorClassifier.boundedRetryAfter(retryAfterSeconds.doubleValue());
            if (boundedHint != null) {
                delay = Math.max(delay, (long) (boundedHint.doubleValue() * 1000.0d));
            }
        }
        return Math.min(delay, (long) (ErrorClassifier.MAX_RETRY_AFTER_SECONDS * 1000.0d));
    }

    @Override
    public void sleep(int attempt, Double retryAfterSeconds) throws InternalException {
        try {
            Thread.sleep(delayMillis(attempt, retryAfterSeconds));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("retry interrupted");
            interrupted.initCause(error);
            throw new InternalException(503, interrupted.getMessage(), null, interrupted);
        }
    }
}
