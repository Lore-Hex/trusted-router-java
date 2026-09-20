package com.trustedrouter.models;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.List;

/** Provider catalog envelope. */
public final class ProviderList extends JsonModel {
    private List<ProviderInfo> data;
    /**
     * Returns data.
     *
     * @return the data
     */
    public List<ProviderInfo> getData() {
        return data == null ? Collections.<ProviderInfo>emptyList() : Collections.unmodifiableList(data);
    }
    /**
     * Represents provider info.
     */
    public static final class ProviderInfo {
        private String id;
        private String name;
        private Boolean zeroDataRetention;
        private Boolean confidential;
        private JsonObject privacy;
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
        /**
         * Returns zero data retention.
         *
         * @return the zero data retention
         */
        public Boolean getZeroDataRetention() { return zeroDataRetention; }
        /**
         * Returns confidential.
         *
         * @return the confidential
         */
        public Boolean getConfidential() { return confidential; }
        /**
         * Returns privacy.
         *
         * @return the privacy
         */
        public JsonObject getPrivacy() { return privacy; }
    }
}
