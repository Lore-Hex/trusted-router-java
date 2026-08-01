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
    public static Builder builder() { return new Builder(); }
    public Boolean getOpenWeights() { return openWeights; }
    public String getProviderJurisdiction() { return providerJurisdiction; }
    public String getProviderRegion() { return providerRegion; }
    public static final class Builder {
        private Boolean openWeights;
        private String providerJurisdiction;
        private String providerRegion;
        private Builder() {}
        public Builder openWeights(boolean value) { openWeights = Boolean.valueOf(value); return this; }
        public Builder providerJurisdiction(String value) { providerJurisdiction = value; return this; }
        public Builder providerRegion(String value) { providerRegion = value; return this; }
        public ModelFilters build() { return new ModelFilters(this); }
    }
}
