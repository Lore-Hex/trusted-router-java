package com.trustedrouter.requests;

/** Optional model-catalog filters. */
public final class ModelFilters {
    private final Boolean openWeights;
    private final String providerJurisdiction;
    private final String providerRegion;

    private ModelFilters(Builder builder) {
        openWeights = builder.openWeights;
        providerJurisdiction = builder.providerJurisdiction;
        providerRegion = builder.providerRegion;
    }
    /**
     * Creates a builder for this value.
     *
     * @return a new builder
     */
    public static Builder builder() { return new Builder(); }
    /**
     * Returns open weights.
     *
     * @return the open weights
     */
    public Boolean getOpenWeights() { return openWeights; }
    /**
     * Returns provider jurisdiction.
     *
     * @return the provider jurisdiction
     */
    public String getProviderJurisdiction() { return providerJurisdiction; }
    /**
     * Returns provider region.
     *
     * @return the provider region
     */
    public String getProviderRegion() { return providerRegion; }
    /**
     * Represents builder.
     */
    public static final class Builder {
        private Boolean openWeights;
        private String providerJurisdiction;
        private String providerRegion;
        private Builder() {}
        /**
         * Sets open weights.
         *
         * @param value the open weights
         * @return this builder
         */
        public Builder openWeights(boolean value) { openWeights = Boolean.valueOf(value); return this; }
        /**
         * Sets provider jurisdiction.
         *
         * @param value the provider jurisdiction
         * @return this builder
         */
        public Builder providerJurisdiction(String value) { providerJurisdiction = value; return this; }
        /**
         * Sets provider region.
         *
         * @param value the provider region
         * @return this builder
         */
        public Builder providerRegion(String value) { providerRegion = value; return this; }
        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        public ModelFilters build() { return new ModelFilters(this); }
    }
}
