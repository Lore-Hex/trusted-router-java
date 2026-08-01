package com.trustedrouter.oauth;

/** Browser URL and local secrets needed to complete delegated-key OAuth. */
public final class OAuthAuthorization {
    private final String codeVerifier;
    private final String codeChallenge;
    private final String codeChallengeMethod;
    private final String state;
    private final String url;
    public OAuthAuthorization(OAuthPkcePair pkce, String state, String url) {
        this.codeVerifier = pkce.getCodeVerifier();
        this.codeChallenge = pkce.getCodeChallenge();
        this.codeChallengeMethod = pkce.getCodeChallengeMethod();
        this.state = state;
        this.url = url;
    }
    public String getCodeVerifier() { return codeVerifier; }
    public String getCodeChallenge() { return codeChallenge; }
    public String getCodeChallengeMethod() { return codeChallengeMethod; }
    public String getState() { return state; }
    public String getUrl() { return url; }
}
