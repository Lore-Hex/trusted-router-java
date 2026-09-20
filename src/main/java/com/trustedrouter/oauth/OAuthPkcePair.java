package com.trustedrouter.oauth;

/** RFC 7636 S256 verifier/challenge pair. Keep the verifier private. */
public final class OAuthPkcePair {
    private final String codeVerifier;
    private final String codeChallenge;
    private final String codeChallengeMethod;
    /**
     * Creates a OAuthPkcePair.
     *
     * @param verifier the verifier
     * @param challenge the challenge
     */
    public OAuthPkcePair(String verifier, String challenge) {
        this.codeVerifier = verifier;
        this.codeChallenge = challenge;
        this.codeChallengeMethod = "S256";
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
}
