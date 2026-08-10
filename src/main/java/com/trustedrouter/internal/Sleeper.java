package com.trustedrouter.internal;

import com.trustedrouter.errors.InternalException;

/**
 * L1 side-effect seam: the ONLY component allowed to sleep. Internal
 * interface with no compatibility guarantees.
 *
 * <p>Injectable so unit tests can record requested delays instead of
 * actually waiting.
 */
public interface Sleeper {
    /**
     * Sleeps before retry number {@code attempt + 1}.
     *
     * @param attempt the zero-based attempt that just failed
     * @param retryAfterSeconds server-requested floor in seconds, or null;
     *     a floor is a max against the jittered delay, never a replacement
     * @throws InternalException when the wait is interrupted
     */
    void sleep(int attempt, Double retryAfterSeconds) throws InternalException;
}
