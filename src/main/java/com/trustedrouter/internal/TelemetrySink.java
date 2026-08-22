package com.trustedrouter.internal;

import java.util.List;

/**
 * Where a finished logical inference call's telemetry goes (client telemetry
 * contract v1). Internal interface with no compatibility guarantees; mirrors
 * the Python SDK's {@code TelemetrySink} protocol.
 *
 * <p>The production sink is {@link TelemetryReporter}, the beacon channel.
 * The recorder calls {@link #onRequest} exactly once per logical call, from
 * the caller's thread, and never lets a sink failure reach the request
 * path (&sect;2.2).
 */
public interface TelemetrySink {
    /**
     * Receives one finished call: the unsampled event (the reporter decides
     * sampling) and its exact counter increments — one request-level row
     * plus one attempt-level row per attempt (&sect;5.4).
     */
    void onRequest(RequestRecorder.Event event, List<RequestRecorder.CounterUpdate> counters);
}
