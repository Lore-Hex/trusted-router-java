package com.trustedrouter.models;

/** Result from the Responses input-token counting endpoint. */
public final class ResponseInputTokens extends JsonModel {
    private int inputTokens;
    private Integer totalTokens;
    public int getInputTokens() { return inputTokens; }
    public Integer getTotalTokens() { return totalTokens; }
}
