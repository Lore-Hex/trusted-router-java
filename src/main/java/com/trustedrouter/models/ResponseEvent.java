package com.trustedrouter.models;

import com.google.gson.JsonObject;

/** One parsed Responses API SSE event. */
public final class ResponseEvent {
    private final String event;
    private final JsonObject data;

    public ResponseEvent(String event, JsonObject data) {
        this.event = event;
        this.data = data;
    }
    public String getEvent() { return event; }
    public JsonObject getData() { return data; }
}
