package com.trustedrouter.models;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.List;

/** Provider catalog envelope. */
public final class ProviderList extends JsonModel {
    private List<ProviderInfo> data;
    public List<ProviderInfo> getData() {
        return data == null ? Collections.<ProviderInfo>emptyList() : Collections.unmodifiableList(data);
    }
    public static final class ProviderInfo {
        private String id;
        private String name;
        private Boolean zeroDataRetention;
        private Boolean confidential;
        private JsonObject privacy;
        public String getId() { return id; }
        public String getName() { return name; }
        public Boolean getZeroDataRetention() { return zeroDataRetention; }
        public Boolean getConfidential() { return confidential; }
        public JsonObject getPrivacy() { return privacy; }
    }
}
