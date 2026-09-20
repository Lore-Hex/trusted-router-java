package com.trustedrouter.oauth;

/** Validated OAuth callback containing the one-time authorization code. */
public final class OAuthCallback {
    private final String code;
    private final String state;

    OAuthCallback(String code, String state) {
        this.code = code;
        this.state = state;
    }

    /**
     * Returns code.
     *
     * @return the code
     */
    public String getCode() { return code; }
    /**
     * Returns state.
     *
     * @return the state
     */
    public String getState() { return state; }
}
