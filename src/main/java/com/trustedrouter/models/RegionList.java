package com.trustedrouter.models;

import java.util.Collections;
import java.util.List;

/** Regional gateway catalog envelope. */
public final class RegionList extends JsonModel {
    private List<RegionInfo> data;
    /**
     * Returns data.
     *
     * @return the data
     */
    public List<RegionInfo> getData() {
        return data == null ? Collections.<RegionInfo>emptyList() : Collections.unmodifiableList(data);
    }
    /**
     * Represents region info.
     */
    public static final class RegionInfo {
        private String id;
        private String name;
        /**
         * Returns id.
         *
         * @return the id
         */
        public String getId() { return id; }
        /**
         * Returns name.
         *
         * @return the name
         */
        public String getName() { return name; }
    }
}
