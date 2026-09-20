package com.trustedrouter.models;

import com.google.gson.JsonObject;

/** One parsed Responses API SSE event. */
public final class ResponseEvent {
    private final String event;
    private final JsonObject data;

    /**
     * Creates a ResponseEvent.
     *
     * @param event the event
     * @param data the data
     */
    public ResponseEvent(String event, JsonObject data) {
        this.event = event;
        this.data = data;
    }
    /**
     * Returns event.
     *
     * @return the event
     */
    public String getEvent() { return event; }
    /**
     * Returns data.
     *
     * @return the data
     */
    public JsonObject getData() { return data; }
}
