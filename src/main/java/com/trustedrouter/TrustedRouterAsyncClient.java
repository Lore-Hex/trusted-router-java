package com.trustedrouter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trustedrouter.attestation.AttestationPolicy;
import com.trustedrouter.attestation.GatewayAttestation;
import com.trustedrouter.models.ActivityResponse;
import com.trustedrouter.models.AuthSessionResponse;
import com.trustedrouter.models.BroadcastDestination;
import com.trustedrouter.models.BroadcastDestinationList;
import com.trustedrouter.models.ChatCompletion;
import com.trustedrouter.models.ChatCompletionChunk;
import com.trustedrouter.models.CheckoutResponse;
import com.trustedrouter.models.CreditsBalance;
import com.trustedrouter.models.EmbeddingResponse;
import com.trustedrouter.models.LogoutResponse;
import com.trustedrouter.models.MessagesResponse;
import com.trustedrouter.models.ModelList;
import com.trustedrouter.models.ProviderList;
import com.trustedrouter.models.RegionList;
import com.trustedrouter.models.ResponseEvent;
import com.trustedrouter.models.ResponseInputTokens;
import com.trustedrouter.models.ResponseObject;
import com.trustedrouter.models.TrustRelease;
import com.trustedrouter.models.UserInfoResponse;
import com.trustedrouter.oauth.OAuthAuthorization;
import com.trustedrouter.oauth.OAuthAuthorizeOptions;
import com.trustedrouter.oauth.OAuthToken;
import com.trustedrouter.requests.BillingCheckoutRequest;
import com.trustedrouter.requests.BroadcastDestinationRequest;
import com.trustedrouter.requests.ChatRequest;
import com.trustedrouter.requests.EmbeddingsRequest;
import com.trustedrouter.requests.FusionRequest;
import com.trustedrouter.requests.MessagesRequest;
import com.trustedrouter.requests.ModelFilters;
import com.trustedrouter.requests.ResponsesRequest;
import com.trustedrouter.streaming.EventStream;
import com.trustedrouter.streaming.TextStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/** {@link CompletableFuture}-based facade over the thread-safe client. */
public final class TrustedRouterAsyncClient {
    private final TrustedRouterClient client;
    private final Executor executor;

    TrustedRouterAsyncClient(TrustedRouterClient client, Executor executor) {
        this.client = client;
        this.executor = executor == null ? ForkJoinPool.commonPool() : executor;
    }

    public CompletableFuture<JsonElement> request(
            String method, String path, JsonElement body, CallOptions options) {
        return submit(() -> client.request(method, path, body, options));
    }
    public CompletableFuture<JsonElement> controlRequest(
            String method, String path, JsonElement body, CallOptions options) {
        return submit(() -> client.controlRequest(method, path, body, options));
    }
    public CompletableFuture<okhttp3.Response> rawRequest(
            String method, String path, JsonElement body, CallOptions options) {
        return submit(() -> client.rawRequest(method, path, body, options));
    }
    public CompletableFuture<okhttp3.Response> rawControlRequest(
            String method, String path, JsonElement body, CallOptions options) {
        return submit(() -> client.rawControlRequest(method, path, body, options));
    }
    public CompletableFuture<ChatCompletion> chatCompletions(ChatRequest request) {
        return submit(() -> client.chatCompletions(request));
    }
    public CompletableFuture<EventStream<ChatCompletionChunk>> chatCompletionsChunks(ChatRequest request) {
        return submit(() -> client.chatCompletionsChunks(request));
    }
    public CompletableFuture<TextStream> chatCompletionsText(ChatRequest request) {
        return submit(() -> client.chatCompletionsText(request));
    }
    public CompletableFuture<InputStream> chatCompletionsRawStream(ChatRequest request) {
        return submit(() -> client.chatCompletionsRawStream(request));
    }
    public CompletableFuture<ChatCompletion> fusion(ChatRequest request) {
        return submit(() -> client.fusion(request));
    }
    public CompletableFuture<ChatCompletion> fusion(FusionRequest request) {
        return submit(() -> client.fusion(request));
    }
    public CompletableFuture<ChatCompletion> synth(FusionRequest request) {
        return submit(() -> client.synth(request));
    }
    public CompletableFuture<ModelList> models() { return submit(client::models); }
    public CompletableFuture<ModelList> models(ModelFilters filters) {
        return submit(() -> client.models(filters));
    }
    public CompletableFuture<ProviderList> providers() { return submit(client::providers); }
    public CompletableFuture<RegionList> regions() { return submit(client::regions); }
    public CompletableFuture<CreditsBalance> credits() { return submit(client::credits); }
    public CompletableFuture<CreditsBalance> credits(CallOptions options) {
        return submit(() -> client.credits(options));
    }
    public CompletableFuture<EmbeddingResponse> embeddings(EmbeddingsRequest request) {
        return submit(() -> client.embeddings(request));
    }
    public CompletableFuture<MessagesResponse> messages(MessagesRequest request) {
        return submit(() -> client.messages(request));
    }
    public CompletableFuture<ResponseObject> responses(ResponsesRequest request) {
        return submit(() -> client.responses(request));
    }
    public CompletableFuture<EventStream<ResponseEvent>> responsesEvents(ResponsesRequest request) {
        return submit(() -> client.responsesEvents(request));
    }
    public CompletableFuture<InputStream> responsesRawStream(ResponsesRequest request) {
        return submit(() -> client.responsesRawStream(request));
    }
    public CompletableFuture<ResponseInputTokens> responsesInputTokens(ResponsesRequest request) {
        return submit(() -> client.responsesInputTokens(request));
    }
    public CompletableFuture<BroadcastDestinationList> broadcastDestinations(CallOptions options) {
        return submit(() -> client.broadcastDestinations(options));
    }
    public CompletableFuture<BroadcastDestinationList> broadcastDestinations() {
        return submit(client::broadcastDestinations);
    }
    public CompletableFuture<BroadcastDestination> createBroadcastDestination(
            BroadcastDestinationRequest request) {
        return submit(() -> client.createBroadcastDestination(request));
    }
    public CompletableFuture<BroadcastDestination> getBroadcastDestination(
            String id, CallOptions options) {
        return submit(() -> client.getBroadcastDestination(id, options));
    }
    public CompletableFuture<BroadcastDestination> getBroadcastDestination(String id) {
        return submit(() -> client.getBroadcastDestination(id));
    }
    public CompletableFuture<BroadcastDestination> updateBroadcastDestination(
            String id, JsonObject patch, CallOptions options) {
        return submit(() -> client.updateBroadcastDestination(id, patch, options));
    }
    public CompletableFuture<JsonElement> deleteBroadcastDestination(String id, CallOptions options) {
        return submit(() -> client.deleteBroadcastDestination(id, options));
    }
    public CompletableFuture<JsonElement> deleteBroadcastDestination(String id) {
        return submit(() -> client.deleteBroadcastDestination(id));
    }
    public CompletableFuture<JsonElement> testBroadcastDestination(String id, CallOptions options) {
        return submit(() -> client.testBroadcastDestination(id, options));
    }
    public CompletableFuture<JsonElement> testBroadcastDestination(String id) {
        return submit(() -> client.testBroadcastDestination(id));
    }
    public CompletableFuture<CheckoutResponse> billingCheckout(BillingCheckoutRequest request) {
        return submit(() -> client.billingCheckout(request));
    }
    public CompletableFuture<CheckoutResponse> stablecoinCheckout(BillingCheckoutRequest request) {
        return submit(() -> client.stablecoinCheckout(request));
    }
    public CompletableFuture<AuthSessionResponse> authSession() { return submit(client::authSession); }
    public CompletableFuture<LogoutResponse> logout() { return submit(client::logout); }
    public CompletableFuture<UserInfoResponse> userInfo() { return submit(client::userInfo); }
    public String oauthAuthorizeUrl(OAuthAuthorizeOptions options) {
        return client.oauthAuthorizeUrl(options);
    }
    public OAuthAuthorization createOAuthAuthorization(OAuthAuthorizeOptions options) {
        return client.createOAuthAuthorization(options);
    }
    public OAuthAuthorization createOAuthAuthorization(
            OAuthAuthorizeOptions options, String codeVerifier) {
        return client.createOAuthAuthorization(options, codeVerifier);
    }
    public CompletableFuture<OAuthToken> exchangeOAuthKey(
            String code, String codeVerifier, String method) {
        return submit(() -> client.exchangeOAuthKey(code, codeVerifier, method));
    }
    public CompletableFuture<ActivityResponse> activity(Map<String, String> parameters) {
        return submit(() -> client.activity(parameters));
    }
    public CompletableFuture<ActivityResponse> activity() { return submit(client::activity); }
    public CompletableFuture<JsonObject> status() { return submit(client::status); }
    public CompletableFuture<JsonObject> status(String url) {
        return submit(() -> client.status(url));
    }
    public CompletableFuture<byte[]> attestation() { return submit(client::attestation); }
    public CompletableFuture<byte[]> attestation(String nonceHex) {
        return submit(() -> client.attestation(nonceHex));
    }
    public CompletableFuture<TrustRelease> trustRelease() { return submit(client::trustRelease); }
    public CompletableFuture<TrustRelease> trustRelease(String url) {
        return submit(() -> client.trustRelease(url));
    }
    public CompletableFuture<GatewayAttestation> verifyGatewayAttestation(AttestationPolicy policy) {
        return submit(() -> client.verifyGatewayAttestation(policy));
    }

    private <T> CompletableFuture<T> submit(CheckedSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }, executor);
    }

    private interface CheckedSupplier<T> { T get() throws Exception; }
}
