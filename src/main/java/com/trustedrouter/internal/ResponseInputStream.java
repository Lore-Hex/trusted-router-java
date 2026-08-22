package com.trustedrouter.internal;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import okhttp3.Response;

/**
 * Input stream that owns and closes its OkHttp response.
 *
 * <p>Carries the raw-stream telemetry hooks of the client telemetry
 * contract: the first body byte marks TTFT (&sect;5.3 allows the first body
 * byte for streams), a read failure marks the attempt {@code stream_broken},
 * end of stream finishes the record, and closing before the end marks it
 * {@code aborted}.
 */
public final class ResponseInputStream extends FilterInputStream {
    private final Response response;
    private final RequestRecorder recorder;
    private boolean bodyStarted;
    private boolean telemetryFinished;

    public ResponseInputStream(Response response, InputStream input) {
        this(response, input, null);
    }

    /** Wraps a raw body, driving the engine's telemetry recorder (may be null). */
    public ResponseInputStream(Response response, InputStream input, RequestRecorder recorder) {
        super(input);
        this.response = response;
        this.recorder = recorder;
    }

    @Override public int read() throws IOException {
        try {
            int value = super.read();
            observe(value < 0 ? -1 : 1);
            return value;
        } catch (IOException failure) {
            fail(failure);
            throw failure;
        }
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        try {
            int count = super.read(buffer, offset, length);
            observe(count);
            return count;
        } catch (IOException failure) {
            fail(failure);
            throw failure;
        }
    }

    @Override public void close() throws IOException {
        try {
            super.close();
        } finally {
            response.close();
            finishTelemetry(true);
        }
    }

    private void observe(int count) {
        if (count > 0 && !bodyStarted) {
            bodyStarted = true;
            if (recorder != null) {
                recorder.onFirstEvent();
            }
        } else if (count < 0) {
            finishTelemetry(false);
        }
    }

    private void fail(IOException failure) {
        if (recorder != null && !telemetryFinished) {
            recorder.onTransportError(failure, true, bodyStarted);
        }
        finishTelemetry(false);
    }

    private void finishTelemetry(boolean aborted) {
        if (recorder == null || telemetryFinished) {
            return;
        }
        telemetryFinished = true;
        if (aborted) {
            recorder.onAborted();
        }
        recorder.finish();
    }
}
