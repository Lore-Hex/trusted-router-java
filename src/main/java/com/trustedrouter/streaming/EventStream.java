package com.trustedrouter.streaming;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trustedrouter.errors.InternalException;
import com.trustedrouter.internal.JsonSupport;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Blocking, closeable SSE reader that works on the JVM and Android. */
public final class EventStream<T> implements Closeable {
    /** Converts one SSE frame into a typed value. Return null to skip the frame. */
    public interface Mapper<T> {
        T map(String event, JsonObject data) throws IOException;
    }

    private final Response response;
    private final BufferedReader reader;
    private final Mapper<T> mapper;
    private boolean finished;

    public EventStream(Response response, Mapper<T> mapper) throws IOException {
        this.response = response;
        ResponseBody body = response.body();
        if (body == null) {
            response.close();
            throw new IOException("TrustedRouter stream had no response body");
        }
        this.reader = new BufferedReader(body.charStream());
        this.mapper = mapper;
    }

    /** Reads the next typed event, or null after {@code [DONE]}. Unexpected EOF fails closed. */
    public T read() throws IOException {
        try {
            while (!finished) {
                Frame frame = readFrame();
                if (frame == null) {
                    throw new InternalException(
                            502, "TrustedRouter stream ended before [DONE]", null);
                }
                if (frame.done) {
                    finished = true;
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
                T mapped = mapper.map(frame.event, object);
                if (mapped != null) {
                    return mapped;
                }
            }
            return null;
        } catch (IOException | RuntimeException error) {
            close();
            throw error;
        }
    }

    public boolean isFinished() {
        return finished;
    }

    @Override
    public void close() {
        finished = true;
        response.close();
    }

    private Frame readFrame() throws IOException {
        String event = null;
        List<String> data = new ArrayList<String>();
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                if (event == null && data.isEmpty()) {
                    return null;
                }
                break;
            }
            if (line.isEmpty()) {
                if (event == null && data.isEmpty()) {
                    continue;
                }
                break;
            }
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
                data.add(value);
            }
        }
        String joined = joinLines(data);
        return new Frame(event, joined, "[DONE]".equals(joined.trim()));
    }

    private static String joinLines(List<String> lines) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) { value.append('\n'); }
            value.append(lines.get(i));
        }
        return value.toString();
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
