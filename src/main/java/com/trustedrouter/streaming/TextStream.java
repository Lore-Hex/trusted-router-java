package com.trustedrouter.streaming;

import com.trustedrouter.models.ChatCompletionChunk;
import java.io.Closeable;
import java.io.IOException;

/** Text-only view over a typed chat chunk stream. */
public final class TextStream implements Closeable {
    private final EventStream<ChatCompletionChunk> chunks;
    public TextStream(EventStream<ChatCompletionChunk> chunks) { this.chunks = chunks; }
    /** Returns the next non-empty text delta, or null at end of stream. */
    public String read() throws IOException {
        while (true) {
            ChatCompletionChunk chunk = chunks.read();
            if (chunk == null) { return null; }
            String text = chunk.textDelta();
            if (!text.isEmpty()) { return text; }
        }
    }
    @Override public void close() { chunks.close(); }
}
