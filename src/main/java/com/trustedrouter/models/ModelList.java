package com.trustedrouter.models;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.List;

/** TrustedRouter model catalog envelope. */
public final class ModelList extends JsonModel {
    private List<ModelInfo> data;

    public List<ModelInfo> getData() {
        return data == null ? Collections.<ModelInfo>emptyList() : Collections.unmodifiableList(data);
    }

    public ModelInfo byId(String id) {
        for (ModelInfo model : getData()) {
            if (model.id != null && model.id.equals(id)) {
                return model;
            }
        }
        return null;
    }

    public static final class ModelInfo {
        private String id;
        private String object;
        private long created;
        private String ownedBy;
        private String name;
        private String description;
        private Integer contextLength;
        private Pricing pricing;
        private JsonObject architecture;
        private JsonObject topProvider;
        private JsonObject perRequestLimits;
        private TrustedRouterMetadata trustedrouter;

        public String getId() { return id; }
        public String getObject() { return object; }
        public long getCreated() { return created; }
        public String getOwnedBy() { return ownedBy; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public Integer getContextLength() { return contextLength; }
        public Pricing getPricing() { return pricing; }
        public JsonObject getArchitecture() { return architecture; }
        public JsonObject getTopProvider() { return topProvider; }
        public JsonObject getPerRequestLimits() { return perRequestLimits; }
        public TrustedRouterMetadata getTrustedRouter() { return trustedrouter; }
        public boolean isOpenWeights() {
            return trustedrouter != null && Boolean.TRUE.equals(trustedrouter.openWeights);
        }
        public boolean hasUsProvider() {
            return trustedrouter != null && Boolean.TRUE.equals(trustedrouter.usProviderAvailable);
        }
        public boolean hasEuFocusedProvider() {
            return trustedrouter != null && Boolean.TRUE.equals(trustedrouter.euFocusedProviderAvailable);
        }
    }

    public static final class Pricing {
        private String prompt;
        private String completion;
        private String promptMax;
        private String completionMax;
        public String getPrompt() { return prompt; }
        public String getCompletion() { return completion; }
        public String getPromptMax() { return promptMax; }
        public String getCompletionMax() { return completionMax; }
    }

    public static final class TrustedRouterMetadata {
        private Boolean openWeights;
        private Boolean usProviderAvailable;
        private Boolean euFocusedProviderAvailable;
        public Boolean getOpenWeights() { return openWeights; }
        public Boolean getUsProviderAvailable() { return usProviderAvailable; }
        public Boolean getEuFocusedProviderAvailable() { return euFocusedProviderAvailable; }
    }
}
