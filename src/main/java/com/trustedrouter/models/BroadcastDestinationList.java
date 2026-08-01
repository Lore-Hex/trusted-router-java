package com.trustedrouter.models;

import java.util.Collections;
import java.util.List;

/** Broadcast destination list envelope. */
public final class BroadcastDestinationList extends JsonModel {
    private List<BroadcastDestination> data;
    public List<BroadcastDestination> getData() {
        return data == null
                ? Collections.<BroadcastDestination>emptyList() : Collections.unmodifiableList(data);
    }
}
