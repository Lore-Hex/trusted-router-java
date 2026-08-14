package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustedrouter.internal.ErrorClassifier;
import com.trustedrouter.internal.JitterSleeper;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Property tests for the Retry-After bound.
 *
 * <p>Retry-After arrives from whatever answered the socket &mdash; the gateway,
 * a proxy, an alias domain &mdash; so it is untrusted input, and it was applied
 * as an <em>uncapped</em> floor on the backoff sleep. The law:
 *
 * <pre>
 * for every attempt a and every header value v,
 *     boundedRetryAfter(v) is null, or finite and in [0, MAX_RETRY_AFTER_SECONDS]
 *     delayMillis(a, ..)   is in [0, MAX_RETRY_AFTER_SECONDS * 1000]
 * </pre>
 *
 * <p>Two Java facts made this worse here than the same defect was elsewhere,
 * and both are language guarantees rather than guesses:
 *
 * <ul>
 *   <li>{@link Double#parseDouble} accepts {@code "Infinity"}, {@code "+Infinity"}
 *       and {@code "NaN"} per the {@code Double.valueOf} grammar, and the old
 *       {@code parsed >= 0.0d} test passes for {@code +Infinity}.
 *   <li>JLS 5.1.3 narrowing from {@code double} to {@code long} <em>saturates</em>
 *       rather than throwing: {@code NaN} becomes {@code 0L}, and
 *       {@code +Infinity} or anything above the long range becomes
 *       {@code Long.MAX_VALUE}. That reaches {@code Thread.sleep} as roughly
 *       292 million years, with no exception to catch and nothing to notice.
 * </ul>
 *
 * <p>A plain {@code Retry-After: 100000} needed no exotic behaviour at all: it
 * parks the caller for 27.8 hours per attempt.
 *
 * <p>Mirrors {@code tests/test_retry_after_bounds.py},
 * {@code test/retry-after-bounds.test.js}, {@code retry_after_bounds_test.go}
 * and the Rust {@code retry_after_bound_tests} module.
 */
class RetryAfterBoundsPropertyTest {

    private static final long CEILING_MILLIS =
            (long) (ErrorClassifier.MAX_RETRY_AFTER_SECONDS * 1000.0d);

    /** Every double a header can turn into, including the ones that saturate. */
    private static final List<Double> HOSTILE = Arrays.asList(
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            Double.NaN,
            Double.MAX_VALUE,
            Double.MIN_VALUE,
            1e300d,
            1e9d,
            100000.0d,
            86400.0d,
            -5.0d,
            -0.001d,
            0.0d,
            0.5d,
            30.0d,
            59.999d,
            60.0d,
            60.001d);

    // ----------------------------------------------------------- the law ---

    @Test
    void aBoundedHintIsNullOrFiniteAndWithinTheCeiling() {
        for (Double seconds : HOSTILE) {
            Double bounded = ErrorClassifier.boundedRetryAfter(seconds.doubleValue());
            if (bounded == null) {
                continue;
            }
            assertThat(Double.isNaN(bounded) || Double.isInfinite(bounded))
                    .as("non-finite hint from %s", seconds)
                    .isFalse();
            assertThat(bounded)
                    .as("unbounded hint from %s", seconds)
                    .isBetween(0.0d, ErrorClassifier.MAX_RETRY_AFTER_SECONDS);
        }
    }

    @Test
    void theSleepThatReachesThreadSleepIsAlwaysBounded() {
        JitterSleeper sleeper = new JitterSleeper();
        // Attempt is quantified too: the backoff ceiling is exponential in it,
        // and 1L << bounded is its own overflow path independent of the header.
        int[] attempts = {0, 1, 5, 6, 7, 31, 32, 63, 64, 1000, Integer.MAX_VALUE, -1};
        for (int attempt : attempts) {
            for (Double seconds : HOSTILE) {
                long delay = sleeper.delayMillis(attempt, seconds);
                assertThat(delay)
                        .as("attempt %s with Retry-After %s produced %s ms", attempt, seconds, delay)
                        .isBetween(0L, CEILING_MILLIS);
            }
            assertThat(sleeper.delayMillis(attempt, null)).isBetween(0L, CEILING_MILLIS);
        }
    }

    // -------------------------------------------------- the saturation bug ---

    @Test
    void theValuesThatSaturatedToLongMaxValueAreRejectedOrClamped() {
        // Before the bound these narrowed to Long.MAX_VALUE milliseconds.
        assertThat(ErrorClassifier.boundedRetryAfter(Double.POSITIVE_INFINITY)).isNull();
        assertThat(ErrorClassifier.boundedRetryAfter(Double.NaN)).isNull();
        assertThat(ErrorClassifier.boundedRetryAfter(-5.0d)).isNull();
        assertThat(ErrorClassifier.boundedRetryAfter(1e300d))
                .isEqualTo(ErrorClassifier.MAX_RETRY_AFTER_SECONDS);
        assertThat(ErrorClassifier.boundedRetryAfter(100000.0d))
                .isEqualTo(ErrorClassifier.MAX_RETRY_AFTER_SECONDS);
    }

    @Test
    void narrowingIsStillSaturatingWhichIsWhyTheBoundIsNeeded() {
        // Documents the language behaviour the fix exists to contain, so the
        // reasoning survives even if someone later questions the clamp.
        assertThat((long) (Double.POSITIVE_INFINITY * 1000.0d)).isEqualTo(Long.MAX_VALUE);
        assertThat((long) (Double.NaN * 1000.0d)).isEqualTo(0L);
        assertThat((long) (1e300d * 1000.0d)).isEqualTo(Long.MAX_VALUE);
    }

    // ------------------------------------------ what must not be disturbed ---

    @Test
    void hintsWithinTheBoundAreHonouredExactly() {
        JitterSleeper sleeper = new JitterSleeper();
        for (double seconds : new double[] {0.0d, 0.25d, 1.0d, 30.0d, 59.0d, 60.0d}) {
            Double bounded = ErrorClassifier.boundedRetryAfter(seconds);
            assertThat(bounded).isEqualTo(seconds);
            assertThat(sleeper.delayMillis(0, bounded))
                    .as("a hint inside the bound must still act as a floor")
                    .isGreaterThanOrEqualTo((long) (seconds * 1000.0d));
        }
    }

    @Test
    void theCeilingStaysWellInsideLongRange() {
        assertThat(CEILING_MILLIS).isLessThan(Long.MAX_VALUE / 1000L);
    }
}
