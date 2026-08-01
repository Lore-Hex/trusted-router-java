package com.trustedrouter.models;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.List;

/** Metadata-only request activity. */
public final class ActivityResponse extends JsonModel {
    private List<JsonObject> activities;
    public List<JsonObject> getActivities() {
        return activities == null
                ? Collections.<JsonObject>emptyList() : Collections.unmodifiableList(activities);
    }
}
