package com.trustedrouter.oauth;

/** TrustedRouter authorize-page options. */
public final class OAuthAuthorizeOptions {
    private final String callbackUrl;
    private final String codeChallenge;
    private final String codeChallengeMethod;
    private final String keyLabel;
    private final String limit;
    private final String usageLimitType;
    private final String expiresAt;
    private final String spawnAgent;
    private final String spawnCloud;
    private final String state;

    private OAuthAuthorizeOptions(Builder b) {
        callbackUrl = b.callbackUrl;
        codeChallenge = b.codeChallenge;
        codeChallengeMethod = b.codeChallengeMethod;
        keyLabel = b.keyLabel;
        limit = b.limit;
        usageLimitType = b.usageLimitType;
        expiresAt = b.expiresAt;
        spawnAgent = b.spawnAgent;
        spawnCloud = b.spawnCloud;
        state = b.state;
    }
    /**
     * Creates a builder for this value.
     *
     * @param callbackUrl the callback url
     * @return a new builder
     */
    public static Builder builder(String callbackUrl) { return new Builder(callbackUrl); }
    /**
     * Returns callback url.
     *
     * @return the callback url
     */
    public String getCallbackUrl() { return callbackUrl; }
    /**
     * Returns code challenge.
     *
     * @return the code challenge
     */
    public String getCodeChallenge() { return codeChallenge; }
    /**
     * Returns code challenge method.
     *
     * @return the code challenge method
     */
    public String getCodeChallengeMethod() { return codeChallengeMethod; }
    /**
     * Returns key label.
     *
     * @return the key label
     */
    public String getKeyLabel() { return keyLabel; }
    /**
     * Returns limit.
     *
     * @return the limit
     */
    public String getLimit() { return limit; }
    /**
     * Returns usage limit type.
     *
     * @return the usage limit type
     */
    public String getUsageLimitType() { return usageLimitType; }
    /**
     * Returns expires at.
     *
     * @return the expires at
     */
    public String getExpiresAt() { return expiresAt; }
    /**
     * Returns spawn agent.
     *
     * @return the spawn agent
     */
    public String getSpawnAgent() { return spawnAgent; }
    /**
     * Returns spawn cloud.
     *
     * @return the spawn cloud
     */
    public String getSpawnCloud() { return spawnCloud; }
    /**
     * Returns state.
     *
     * @return the state
     */
    public String getState() { return state; }

    /**
     * Represents builder.
     */
    public static final class Builder {
        private final String callbackUrl;
        private String codeChallenge;
        private String codeChallengeMethod;
        private String keyLabel;
        private String limit;
        private String usageLimitType;
        private String expiresAt;
        private String spawnAgent;
        private String spawnCloud;
        private String state;
        private Builder(String callbackUrl) {
            if (callbackUrl == null || callbackUrl.isEmpty()) {
                throw new IllegalArgumentException("callbackUrl is required");
            }
            this.callbackUrl = callbackUrl;
        }
        /**
         * Sets code challenge.
         *
         * @param value the code challenge
         * @return this builder
         */
        public Builder codeChallenge(String value) { codeChallenge = value; return this; }
        /**
         * Sets code challenge method.
         *
         * @param value the code challenge method
         * @return this builder
         */
        public Builder codeChallengeMethod(String value) { codeChallengeMethod = value; return this; }
        /**
         * Sets key label.
         *
         * @param value the key label
         * @return this builder
         */
        public Builder keyLabel(String value) { keyLabel = value; return this; }
        /**
         * Sets limit.
         *
         * @param exactDollars the exact dollars
         * @return this builder
         */
        public Builder limit(String exactDollars) { limit = exactDollars; return this; }
        /**
         * Sets usage limit type.
         *
         * @param value the usage limit type
         * @return this builder
         */
        public Builder usageLimitType(String value) { usageLimitType = value; return this; }
        /**
         * Sets expires at.
         *
         * @param value the expires at
         * @return this builder
         */
        public Builder expiresAt(String value) { expiresAt = value; return this; }
        /**
         * Sets spawn agent.
         *
         * @param value the spawn agent
         * @return this builder
         */
        public Builder spawnAgent(String value) { spawnAgent = value; return this; }
        /**
         * Sets spawn cloud.
         *
         * @param value the spawn cloud
         * @return this builder
         */
        public Builder spawnCloud(String value) { spawnCloud = value; return this; }
        /**
         * Sets state.
         *
         * @param value the state
         * @return this builder
         */
        public Builder state(String value) { state = value; return this; }
        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        public OAuthAuthorizeOptions build() { return new OAuthAuthorizeOptions(this); }
    }
}
