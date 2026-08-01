package com.trustedrouter.models;

import java.util.Collections;
import java.util.List;

/** Regional gateway catalog envelope. */
public final class RegionList extends JsonModel {
    private List<RegionInfo> data;
    public List<RegionInfo> getData() {
        return data == null ? Collections.<RegionInfo>emptyList() : Collections.unmodifiableList(data);
    }
    public static final class RegionInfo {
        private String id;
        private String name;
        public String getId() { return id; }
        public String getName() { return name; }
    }
}
