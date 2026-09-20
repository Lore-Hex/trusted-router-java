package com.trustedrouter.models;

/** Result from the Responses input-token counting endpoint. */
public final class ResponseInputTokens extends JsonModel {
    private int inputTokens;
    private Integer totalTokens;
    /**
     * Returns input tokens.
     *
     * @return the input tokens
     */
    public int getInputTokens() { return inputTokens; }
    /**
     * Returns total tokens.
     *
     * @return the total tokens
     */
    public Integer getTotalTokens() { return totalTokens; }
}
