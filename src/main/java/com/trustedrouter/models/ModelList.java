package com.trustedrouter.models;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.List;

/** TrustedRouter model catalog envelope. */
public final class ModelList extends JsonModel {
    private List<ModelInfo> data;

    /**
     * Returns data.
     *
     * @return the data
     */
    public List<ModelInfo> getData() {
        return data == null ? Collections.<ModelInfo>emptyList() : Collections.unmodifiableList(data);
    }

    /**
     * Performs the by id operation.
     *
     * @param id the id
     * @return the by id
     */
    public ModelInfo byId(String id) {
        for (ModelInfo model : getData()) {
            if (model.id != null && model.id.equals(id)) {
                return model;
            }
        }
        return null;
    }

    /**
     * Represents model info.
     */
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

        /**
         * Returns id.
         *
         * @return the id
         */
        public String getId() { return id; }
        /**
         * Returns object.
         *
         * @return the object
         */
        public String getObject() { return object; }
        /**
         * Returns created.
         *
         * @return the created
         */
        public long getCreated() { return created; }
        /**
         * Returns owned by.
         *
         * @return the owned by
         */
        public String getOwnedBy() { return ownedBy; }
        /**
         * Returns name.
         *
         * @return the name
         */
        public String getName() { return name; }
        /**
         * Returns description.
         *
         * @return the description
         */
        public String getDescription() { return description; }
        /**
         * Returns context length.
         *
         * @return the context length
         */
        public Integer getContextLength() { return contextLength; }
        /**
         * Returns pricing.
         *
         * @return the pricing
         */
        public Pricing getPricing() { return pricing; }
        /**
         * Returns architecture.
         *
         * @return the architecture
         */
        public JsonObject getArchitecture() { return architecture; }
        /**
         * Returns top provider.
         *
         * @return the top provider
         */
        public JsonObject getTopProvider() { return topProvider; }
        /**
         * Returns per request limits.
         *
         * @return the per request limits
         */
        public JsonObject getPerRequestLimits() { return perRequestLimits; }
        /**
         * Returns trusted router.
         *
         * @return the trusted router
         */
        public TrustedRouterMetadata getTrustedRouter() { return trustedrouter; }
        /**
         * Returns open weights.
         *
         * @return the open weights
         */
        public boolean isOpenWeights() {
            return trustedrouter != null && Boolean.TRUE.equals(trustedrouter.openWeights);
        }
        /**
         * Performs the has us provider operation.
         *
         * @return the has us provider
         */
        public boolean hasUsProvider() {
            return trustedrouter != null && Boolean.TRUE.equals(trustedrouter.usProviderAvailable);
        }
        /**
         * Performs the has eu focused provider operation.
         *
         * @return the has eu focused provider
         */
        public boolean hasEuFocusedProvider() {
            return trustedrouter != null && Boolean.TRUE.equals(trustedrouter.euFocusedProviderAvailable);
        }
    }

    /**
     * Represents pricing.
     */
    public static final class Pricing {
        private String prompt;
        private String completion;
        private String promptMax;
        private String completionMax;
        /**
         * Returns prompt.
         *
         * @return the prompt
         */
        public String getPrompt() { return prompt; }
        /**
         * Returns completion.
         *
         * @return the completion
         */
        public String getCompletion() { return completion; }
        /**
         * Returns prompt max.
         *
         * @return the prompt max
         */
        public String getPromptMax() { return promptMax; }
        /**
         * Returns completion max.
         *
         * @return the completion max
         */
        public String getCompletionMax() { return completionMax; }
    }

    /**
     * Represents trusted router metadata.
     */
    public static final class TrustedRouterMetadata {
        private Boolean openWeights;
        private Boolean usProviderAvailable;
        private Boolean euFocusedProviderAvailable;
        /**
         * Returns open weights.
         *
         * @return the open weights
         */
        public Boolean getOpenWeights() { return openWeights; }
        /**
         * Returns us provider available.
         *
         * @return the us provider available
         */
        public Boolean getUsProviderAvailable() { return usProviderAvailable; }
        /**
         * Returns eu focused provider available.
         *
         * @return the eu focused provider available
         */
        public Boolean getEuFocusedProviderAvailable() { return euFocusedProviderAvailable; }
    }
}
