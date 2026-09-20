package com.trustedrouter.oauth;

/** Browser URL and local secrets needed to complete delegated-key OAuth. */
public final class OAuthAuthorization {
    private final String codeVerifier;
    private final String codeChallenge;
    private final String codeChallengeMethod;
    private final String state;
    private final String url;
    /**
     * Creates a OAuthAuthorization.
     *
     * @param pkce the pkce
     * @param state the state
     * @param url the url
     */
    public OAuthAuthorization(OAuthPkcePair pkce, String state, String url) {
        this.codeVerifier = pkce.getCodeVerifier();
        this.codeChallenge = pkce.getCodeChallenge();
        this.codeChallengeMethod = pkce.getCodeChallengeMethod();
        this.state = state;
        this.url = url;
    }
    /**
     * Returns code verifier.
     *
     * @return the code verifier
     */
    public String getCodeVerifier() { return codeVerifier; }
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
     * Returns state.
     *
     * @return the state
     */
    public String getState() { return state; }
    /**
     * Returns url.
     *
     * @return the url
     */
    public String getUrl() { return url; }
}
