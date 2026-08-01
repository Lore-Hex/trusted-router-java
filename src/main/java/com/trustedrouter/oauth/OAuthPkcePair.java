package com.trustedrouter.oauth;

/** RFC 7636 S256 verifier/challenge pair. Keep the verifier private. */
public final class OAuthPkcePair {
    private final String codeVerifier;
    private final String codeChallenge;
    private final String codeChallengeMethod;
    public OAuthPkcePair(String verifier, String challenge) {
        this.codeVerifier = verifier;
        this.codeChallenge = challenge;
        this.codeChallengeMethod = "S256";
    }
    public String getCodeVerifier() { return codeVerifier; }
    public String getCodeChallenge() { return codeChallenge; }
    public String getCodeChallengeMethod() { return codeChallengeMethod; }
}
