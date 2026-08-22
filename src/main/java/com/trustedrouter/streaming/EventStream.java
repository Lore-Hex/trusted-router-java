package com.trustedrouter.streaming;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trustedrouter.errors.InternalException;
import com.trustedrouter.errors.TrustedRouterException;
import com.trustedrouter.internal.JsonSupport;
import com.trustedrouter.internal.RequestRecorder;
import java.io.Closeable;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

/**
 * Blocking, closeable SSE reader that works on the JVM and Android.
 *
 * <p>This is the SDK's only place where a stream's life after the headers
 * is observable, so it carries the telemetry hooks of the client telemetry
 * contract (&sect;6.1): the first parsed event marks TTFT, a read failure or
 * an end before {@code [DONE]} marks the attempt {@code stream_broken}, and
 * closing before the end marks it {@code aborted}. Application-level
 * failures (malformed JSON, an in-band error envelope, a mapper failure)
 * leave the attempt as the response recorded it, exactly as the Python
 * reference. There is no reconnect path: the engine never retries after the
 * first surfaced body byte.
 */
public final class EventStream<T> implements Closeable {
    /** Maximum bytes in one SSE line or one not-yet-delimited frame. */
    public static final int MAXIMUM_FRAME_BYTES = 1_048_576;

    /** Converts one SSE frame into a typed value. Return null to skip the frame. */
    public interface Mapper<T> {
        T map(String event, JsonObject data) throws IOException;
    }

    private final Response response;
    private final BufferedSource source;
    private final Mapper<T> mapper;
    private final RequestRecorder recorder;
    private boolean finished;
    private boolean bodyStarted;
    private boolean telemetryFinished;

    public EventStream(Response response, Mapper<T> mapper) throws IOException {
        this(response, mapper, null);
    }

    /**
     * Wraps an opened stream, driving the engine's telemetry recorder (may
     * be null) to completion as the stream is consumed.
     */
    public EventStream(Response response, Mapper<T> mapper, RequestRecorder recorder)
            throws IOException {
        this.response = response;
        this.recorder = recorder;
        ResponseBody body = response.body();
        if (body == null) {
            response.close();
            IOException failure = new IOException("TrustedRouter stream had no response body");
            recordTransportFailure(new ProtocolException(failure.getMessage()));
            finishTelemetry(false);
            throw failure;
        }
        this.source = body.source();
        this.mapper = mapper;
    }

    /** Reads the next typed event, or null after {@code [DONE]}. Unexpected EOF fails closed. */
    public T read() throws IOException {
        try {
            while (!finished) {
                Frame frame = readFrameRecorded();
                if (frame == null) {
                    ProtocolException truncated =
                            new ProtocolException("TrustedRouter stream ended before [DONE]");
                    recordTransportFailure(truncated);
                    throw new InternalException(
                            502, "TrustedRouter stream ended before [DONE]", null, truncated);
                }
                if (frame.done) {
                    finished = true;
                    finishTelemetry(false);
                    close();
                    return null;
                }
                if (frame.data == null || frame.data.trim().isEmpty()) {
                    continue;
                }
                JsonElement parsed;
                try {
                    parsed = JsonSupport.parse(frame.data);
                } catch (RuntimeException error) {
                    throw new InternalException(
                            502, "Malformed TrustedRouter SSE JSON", null, error);
                }
                if (!parsed.isJsonObject()) {
                    throw new InternalException(
                            502, "TrustedRouter SSE data must be a JSON object", null);
                }
                JsonObject object = parsed.getAsJsonObject();
                if (object.has("error")) {
                    throw new InternalException(
                            502, JsonSupport.errorMessage(object), object);
                }
                markFirstEvent();
                T mapped = mapper.map(frame.event, object);
                if (mapped != null) {
                    return mapped;
                }
            }
            return null;
        } catch (IOException | RuntimeException error) {
            // A failure ends the logical call as recorded so far; it is not
            // a caller abort, so close() below must not mark one.
            finishTelemetry(false);
            close();
            throw error;
        }
    }

    public boolean isFinished() {
        return finished;
    }

    /**
     * Closes the stream and its connection. Closing before {@code [DONE]}
     * is a caller abort for telemetry; closing afterwards changes nothing.
     */
    @Override
    public void close() {
        finished = true;
        response.close();
        finishTelemetry(true);
    }

    /**
     * Reads one frame, classifying only genuine transport failures — never
     * the SDK's own protocol-violation exceptions — as stream failures.
     */
    private Frame readFrameRecorded() throws IOException {
        try {
            return readFrame();
        } catch (TrustedRouterException protocolViolation) {
            throw protocolViolation;
        } catch (IOException transportFailure) {
            recordTransportFailure(transportFailure);
            throw transportFailure;
        }
    }

    private void markFirstEvent() {
        if (bodyStarted) {
            return;
        }
        bodyStarted = true;
        if (recorder != null) {
            recorder.onFirstEvent();
        }
    }

    private void recordTransportFailure(Throwable failure) {
        if (recorder != null && !telemetryFinished) {
            recorder.onTransportError(failure, true, bodyStarted);
        }
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

    private Frame readFrame() throws IOException {
        String event = null;
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        int frameBytes = 0;
        while (true) {
            BoundedLine bounded = readBoundedLine(MAXIMUM_FRAME_BYTES - frameBytes);
            if (bounded == null) {
                if (event == null && !hasData) {
                    return null;
                }
                break;
            }
            String line = bounded.value;
            if (line.isEmpty()) {
                if (event == null && !hasData) {
                    // Comments and unknown fields form an ignorable frame;
                    // their budget ends at the delimiter just like any
                    // emitted frame rather than accumulating forever.
                    frameBytes = 0;
                    continue;
                }
                break;
            }
            frameBytes += bounded.byteCount;
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if ("event".equals(field)) {
                event = value;
            } else if ("data".equals(field)) {
                if (hasData) {
                    if (frameBytes == MAXIMUM_FRAME_BYTES) {
                        throw oversizedFrame();
                    }
                    data.append('\n');
                    frameBytes++;
                }
                data.append(value);
                hasData = true;
            }
        }
        String joined = data.toString();
        return new Frame(event, joined, "[DONE]".equals(joined.trim()));
    }

    /**
     * Reads at most {@code remainingFrameBytes} before allocating a String.
     * BufferedReader.readLine() cannot be used here because it allocates an
     * attacker-controlled line before the caller can inspect its length.
     */
    private BoundedLine readBoundedLine(int remainingFrameBytes) throws IOException {
        Buffer bytes = new Buffer();
        int limit = Math.min(MAXIMUM_FRAME_BYTES, Math.max(0, remainingFrameBytes));
        while (!source.exhausted()) {
            byte next = source.readByte();
            if (next == (byte) '\n') {
                byte[] value = bytes.readByteArray();
                int length = value.length;
                if (length > 0 && value[length - 1] == (byte) '\r') {
                    length--;
                }
                return new BoundedLine(decodeUtf8(value, length), length);
            }
            if (bytes.size() >= limit) {
                throw oversizedFrame();
            }
            bytes.writeByte(next & 0xff);
        }
        if (bytes.size() == 0L) {
            return null;
        }
        byte[] value = bytes.readByteArray();
        int length = value.length;
        if (length > 0 && value[length - 1] == (byte) '\r') {
            length--;
        }
        return new BoundedLine(decodeUtf8(value, length), length);
    }

    private static String decodeUtf8(byte[] value, int length) throws InternalException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value, 0, length))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new InternalException(
                    502, "TrustedRouter SSE line was not valid UTF-8", null, error);
        }
    }

    private static InternalException oversizedFrame() {
        return new InternalException(
                502,
                "TrustedRouter SSE line or frame exceeded " + MAXIMUM_FRAME_BYTES + " bytes",
                null);
    }

    private static final class BoundedLine {
        private final String value;
        private final int byteCount;

        private BoundedLine(String value, int byteCount) {
            this.value = value;
            this.byteCount = byteCount;
        }
    }

    private static final class Frame {
        private final String event;
        private final String data;
        private final boolean done;
        private Frame(String event, String data, boolean done) {
            this.event = event;
            this.data = data;
            this.done = done;
        }
    }
}
