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
    public static Builder builder(String callbackUrl) { return new Builder(callbackUrl); }
    public String getCallbackUrl() { return callbackUrl; }
    public String getCodeChallenge() { return codeChallenge; }
    public String getCodeChallengeMethod() { return codeChallengeMethod; }
    public String getKeyLabel() { return keyLabel; }
    public String getLimit() { return limit; }
    public String getUsageLimitType() { return usageLimitType; }
    public String getExpiresAt() { return expiresAt; }
    public String getSpawnAgent() { return spawnAgent; }
    public String getSpawnCloud() { return spawnCloud; }
    public String getState() { return state; }

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
        public Builder codeChallenge(String value) { codeChallenge = value; return this; }
        public Builder codeChallengeMethod(String value) { codeChallengeMethod = value; return this; }
        public Builder keyLabel(String value) { keyLabel = value; return this; }
        public Builder limit(String exactDollars) { limit = exactDollars; return this; }
        public Builder usageLimitType(String value) { usageLimitType = value; return this; }
        public Builder expiresAt(String value) { expiresAt = value; return this; }
        public Builder spawnAgent(String value) { spawnAgent = value; return this; }
        public Builder spawnCloud(String value) { spawnCloud = value; return this; }
        public Builder state(String value) { state = value; return this; }
        public OAuthAuthorizeOptions build() { return new OAuthAuthorizeOptions(this); }
    }
}
