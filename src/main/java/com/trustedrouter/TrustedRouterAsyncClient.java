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
import com.trustedrouter.internal.Transport;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * {@link CompletableFuture}-based facade over the thread-safe client.
 *
 * <p>Calls execute on the configured executor (the common pool by default). Failures
 * complete the future exceptionally. Returned streams are still blocking and must be
 * closed by the caller; completing the future only means that the stream was opened.
 */
public final class TrustedRouterAsyncClient {
    private final TrustedRouterClient client;
    private final Executor executor;

    TrustedRouterAsyncClient(TrustedRouterClient client, Executor executor) {
        this.client = client;
        this.executor = executor == null ? ForkJoinPool.commonPool() : executor;
    }

    /**
     * Performs the request operation.
     *
     * @param method the method
     * @param path the path
     * @param body the body
     * @param options the options
     * @return the request
     */
    public CompletableFuture<JsonElement> request(
            String method, String path, JsonElement body, CallOptions options) {
        return submit(() -> client.request(method, path, body, options));
    }
    /**
     * Performs the control request operation.
     *
     * @param method the method
     * @param path the path
     * @param body the body
     * @param options the options
     * @return the control request
     */
    public CompletableFuture<JsonElement> controlRequest(
            String method, String path, JsonElement body, CallOptions options) {
        return submit(() -> client.controlRequest(method, path, body, options));
    }
    /**
     * Performs the raw request operation.
     *
     * @param method the method
     * @param path the path
     * @param body the body
     * @param options the options
     * @return the raw request
     */
    public CompletableFuture<okhttp3.Response> rawRequest(
            String method, String path, JsonElement body, CallOptions options) {
        return submit(() -> client.rawRequest(method, path, body, options));
    }
    /**
     * Performs the raw control request operation.
     *
     * @param method the method
     * @param path the path
     * @param body the body
     * @param options the options
     * @return the raw control request
     */
    public CompletableFuture<okhttp3.Response> rawControlRequest(
            String method, String path, JsonElement body, CallOptions options) {
        return submit(() -> client.rawControlRequest(method, path, body, options));
    }
    /**
     * Runs asynchronously: Requests a non-streaming chat completion on the inference plane.
     *
     * @param request the request
     * @return a future yielding the decoded completion
     */
    public CompletableFuture<ChatCompletion> chatCompletions(ChatRequest request) {
        return submit(() -> client.chatCompletions(request));
    }
    /**
     * Runs asynchronously: Opens a stream of typed chat chunks; the caller must close it.
     *
     * @param request the request
     * @return a future yielding the closeable chunk stream
     */
    public CompletableFuture<EventStream<ChatCompletionChunk>> chatCompletionsChunks(ChatRequest request) {
        return submit(() -> client.chatCompletionsChunks(request));
    }
    /**
     * Runs asynchronously: Opens a stream of chat text deltas; the caller must close it.
     *
     * @param request the request
     * @return a future yielding the closeable text stream
     */
    public CompletableFuture<TextStream> chatCompletionsText(ChatRequest request) {
        return submit(() -> client.chatCompletionsText(request));
    }
    /**
     * Runs asynchronously: Opens the raw chat SSE body; the caller must close it.
     *
     * @param request the request
     * @return a future yielding the response body stream
     */
    public CompletableFuture<InputStream> chatCompletionsRawStream(ChatRequest request) {
        return submit(() -> client.chatCompletionsRawStream(request));
    }
    /**
     * Performs the fusion operation.
     *
     * @param request the request
     * @return the fusion
     */
    public CompletableFuture<ChatCompletion> fusion(ChatRequest request) {
        return submit(() -> client.fusion(request));
    }
    /**
     * Performs the fusion operation.
     *
     * @param request the request
     * @return the fusion
     */
    public CompletableFuture<ChatCompletion> fusion(FusionRequest request) {
        return submit(() -> client.fusion(request));
    }
    /**
     * Performs the synth operation.
     *
     * @param request the request
     * @return the synth
     */
    public CompletableFuture<ChatCompletion> synth(FusionRequest request) {
        return submit(() -> client.synth(request));
    }
    /**
     * Runs asynchronously: Lists models matching the supplied filters, or all models when filters are absent.
     *
     * @return a future yielding the model catalog
     */
    public CompletableFuture<ModelList> models() { return submit(client::models); }
    /**
     * Runs asynchronously: Lists models matching the supplied filters, or all models when filters are absent.
     *
     * @param filters the filters
     * @return a future yielding the model catalog
     */
    public CompletableFuture<ModelList> models(ModelFilters filters) {
        return submit(() -> client.models(filters));
    }
    /**
     * Runs asynchronously: Lists available inference providers.
     *
     * @return a future yielding the provider catalog
     */
    public CompletableFuture<ProviderList> providers() { return submit(client::providers); }
    /**
     * Runs asynchronously: Lists available routing regions.
     *
     * @return a future yielding the region catalog
     */
    public CompletableFuture<RegionList> regions() { return submit(client::regions); }
    /**
     * Runs asynchronously: Reads the authenticated account credit balance.
     *
     * @return a future yielding the credit balance
     */
    public CompletableFuture<CreditsBalance> credits() { return submit(client::credits); }
    /**
     * Runs asynchronously: Reads the authenticated account credit balance.
     *
     * @param options the options
     * @return a future yielding the credit balance
     */
    public CompletableFuture<CreditsBalance> credits(CallOptions options) {
        return submit(() -> client.credits(options));
    }
    /**
     * Runs asynchronously: Requests embeddings on the inference plane.
     *
     * @param request the request
     * @return a future yielding the decoded embeddings
     */
    public CompletableFuture<EmbeddingResponse> embeddings(EmbeddingsRequest request) {
        return submit(() -> client.embeddings(request));
    }
    /**
     * Runs asynchronously: Sends an Anthropic-compatible Messages request.
     *
     * @param request the request
     * @return a future yielding the decoded message
     */
    public CompletableFuture<MessagesResponse> messages(MessagesRequest request) {
        return submit(() -> client.messages(request));
    }
    /**
     * Runs asynchronously: Sends a non-streaming Responses API request.
     *
     * @param request the request
     * @return a future yielding the decoded response
     */
    public CompletableFuture<ResponseObject> responses(ResponsesRequest request) {
        return submit(() -> client.responses(request));
    }
    /**
     * Runs asynchronously: Opens typed Responses SSE events; the caller must close the stream.
     *
     * @param request the request
     * @return a future yielding the closeable event stream
     */
    public CompletableFuture<EventStream<ResponseEvent>> responsesEvents(ResponsesRequest request) {
        return submit(() -> client.responsesEvents(request));
    }
    /**
     * Runs asynchronously: Opens the raw Responses SSE body; the caller must close it.
     *
     * @param request the request
     * @return a future yielding the response body stream
     */
    public CompletableFuture<InputStream> responsesRawStream(ResponsesRequest request) {
        return submit(() -> client.responsesRawStream(request));
    }
    /**
     * Runs asynchronously: Counts input tokens without creating a stored response.
     *
     * @param request the request
     * @return a future yielding the input token count
     */
    public CompletableFuture<ResponseInputTokens> responsesInputTokens(ResponsesRequest request) {
        return submit(() -> client.responsesInputTokens(request));
    }
    /**
     * Runs asynchronously: Lists configured Broadcast destinations on the control plane.
     *
     * @param options the options
     * @return a future yielding the destination list
     */
    public CompletableFuture<BroadcastDestinationList> broadcastDestinations(CallOptions options) {
        return submit(() -> client.broadcastDestinations(options));
    }
    /**
     * Runs asynchronously: Lists configured Broadcast destinations on the control plane.
     *
     * @return a future yielding the destination list
     */
    public CompletableFuture<BroadcastDestinationList> broadcastDestinations() {
        return submit(client::broadcastDestinations);
    }
    /**
     * Runs asynchronously: Creates a Broadcast destination on the control plane.
     *
     * @param request the request
     * @return a future yielding the created destination
     */
    public CompletableFuture<BroadcastDestination> createBroadcastDestination(
            BroadcastDestinationRequest request) {
        return submit(() -> client.createBroadcastDestination(request));
    }
    /**
     * Returns broadcast destination.
     *
     * @param id the id
     * @param options the options
     * @return the broadcast destination
     */
    public CompletableFuture<BroadcastDestination> getBroadcastDestination(
            String id, CallOptions options) {
        return submit(() -> client.getBroadcastDestination(id, options));
    }
    /**
     * Returns broadcast destination.
     *
     * @param id the id
     * @return the broadcast destination
     */
    public CompletableFuture<BroadcastDestination> getBroadcastDestination(String id) {
        return submit(() -> client.getBroadcastDestination(id));
    }
    /**
     * Runs asynchronously: Updates a Broadcast destination on the control plane.
     *
     * @param id the id
     * @param patch the patch
     * @param options the options
     * @return a future yielding the updated destination
     */
    public CompletableFuture<BroadcastDestination> updateBroadcastDestination(
            String id, JsonObject patch, CallOptions options) {
        return submit(() -> client.updateBroadcastDestination(id, patch, options));
    }
    /**
     * Runs asynchronously: Deletes the identified Broadcast destination.
     *
     * @param id the id
     * @param options the options
     * @return a future yielding the deletion response
     */
    public CompletableFuture<JsonElement> deleteBroadcastDestination(String id, CallOptions options) {
        return submit(() -> client.deleteBroadcastDestination(id, options));
    }
    /**
     * Runs asynchronously: Deletes the identified Broadcast destination.
     *
     * @param id the id
     * @return a future yielding the deletion response
     */
    public CompletableFuture<JsonElement> deleteBroadcastDestination(String id) {
        return submit(() -> client.deleteBroadcastDestination(id));
    }
    /**
     * Runs asynchronously: Sends a test event to the identified Broadcast destination.
     *
     * @param id the id
     * @param options the options
     * @return a future yielding the test response
     */
    public CompletableFuture<JsonElement> testBroadcastDestination(String id, CallOptions options) {
        return submit(() -> client.testBroadcastDestination(id, options));
    }
    /**
     * Runs asynchronously: Sends a test event to the identified Broadcast destination.
     *
     * @param id the id
     * @return a future yielding the test response
     */
    public CompletableFuture<JsonElement> testBroadcastDestination(String id) {
        return submit(() -> client.testBroadcastDestination(id));
    }
    /**
     * Runs asynchronously: Creates a billing checkout using a decimal-string amount.
     *
     * @param request the request
     * @return a future yielding the checkout details
     */
    public CompletableFuture<CheckoutResponse> billingCheckout(BillingCheckoutRequest request) {
        return submit(() -> client.billingCheckout(request));
    }
    /**
     * Runs asynchronously: Creates a stablecoin checkout using a decimal-string amount.
     *
     * @param request the request
     * @return a future yielding the checkout details
     */
    public CompletableFuture<CheckoutResponse> stablecoinCheckout(BillingCheckoutRequest request) {
        return submit(() -> client.stablecoinCheckout(request));
    }
    /**
     * Runs asynchronously: Reads the current authenticated session.
     *
     * @return a future yielding the session details
     */
    public CompletableFuture<AuthSessionResponse> authSession() { return submit(client::authSession); }
    /**
     * Runs asynchronously: Logs out the current authenticated session.
     *
     * @return a future yielding the logout response
     */
    public CompletableFuture<LogoutResponse> logout() { return submit(client::logout); }
    /**
     * Runs asynchronously: Reads the authenticated user profile.
     *
     * @return a future yielding the user profile
     */
    public CompletableFuture<UserInfoResponse> userInfo() { return submit(client::userInfo); }
    /**
     * Runs asynchronously: Builds the OAuth authorization URL for user consent.
     *
     * @param options the options
     * @return a future yielding the authorization URL
     */
    public String oauthAuthorizeUrl(OAuthAuthorizeOptions options) {
        return client.oauthAuthorizeUrl(options);
    }
    /**
     * Runs asynchronously: Creates an OAuth authorization URL with state and PKCE material to retain for the callback.
     *
     * @param options the options
     * @return a future yielding the authorization and callback verification material
     */
    public OAuthAuthorization createOAuthAuthorization(OAuthAuthorizeOptions options) {
        return client.createOAuthAuthorization(options);
    }
    /**
     * Runs asynchronously: Creates an OAuth authorization URL with state and PKCE material to retain for the callback.
     *
     * @param options the options
     * @param codeVerifier the code verifier
     * @return a future yielding the authorization and callback verification material
     */
    public OAuthAuthorization createOAuthAuthorization(
            OAuthAuthorizeOptions options, String codeVerifier) {
        return client.createOAuthAuthorization(options, codeVerifier);
    }
    /**
     * Performs the exchange oauth key operation.
     *
     * @param code the code
     * @param codeVerifier the code verifier
     * @param method the method
     * @return the exchange oauth key
     */
    public CompletableFuture<OAuthToken> exchangeOAuthKey(
            String code, String codeVerifier, String method) {
        return submit(() -> client.exchangeOAuthKey(code, codeVerifier, method));
    }
    /**
     * Runs asynchronously: Reads account activity from the control plane.
     *
     * @param parameters the parameters
     * @return a future yielding the activity response
     */
    public CompletableFuture<ActivityResponse> activity(Map<String, String> parameters) {
        return submit(() -> client.activity(parameters));
    }
    /**
     * Runs asynchronously: Reads account activity from the control plane.
     *
     * @return a future yielding the activity response
     */
    public CompletableFuture<ActivityResponse> activity() { return submit(client::activity); }
    /**
     * Runs asynchronously: Fetches public service status without sending credentials.
     *
     * @return a future yielding the status JSON
     */
    public CompletableFuture<JsonObject> status() { return submit(client::status); }
    /**
     * Runs asynchronously: Fetches public service status without sending credentials.
     *
     * @param url the url
     * @return a future yielding the status JSON
     */
    public CompletableFuture<JsonObject> status(String url) {
        return submit(() -> client.status(url));
    }
    /**
     * Runs asynchronously: Fetches gateway attestation evidence bound to the supplied or generated nonce.
     *
     * @return a future yielding the attestation document bytes
     */
    public CompletableFuture<byte[]> attestation() { return submit(client::attestation); }
    /**
     * Runs asynchronously: Fetches gateway attestation evidence bound to the supplied or generated nonce.
     *
     * @param nonceHex the nonce hex
     * @return a future yielding the attestation document bytes
     */
    public CompletableFuture<byte[]> attestation(String nonceHex) {
        return submit(() -> client.attestation(nonceHex));
    }
    /**
     * Runs asynchronously: Fetches public trust release metadata without sending credentials.
     *
     * @return a future yielding the trust release
     */
    public CompletableFuture<TrustRelease> trustRelease() { return submit(client::trustRelease); }
    /**
     * Runs asynchronously: Fetches public trust release metadata without sending credentials.
     *
     * @param url the url
     * @return a future yielding the trust release
     */
    public CompletableFuture<TrustRelease> trustRelease(String url) {
        return submit(() -> client.trustRelease(url));
    }
    /**
     * Runs asynchronously: Fetches and verifies gateway attestation against the supplied policy.
     *
     * @param policy the policy
     * @return a future yielding the verified attestation
     */
    public CompletableFuture<GatewayAttestation> verifyGatewayAttestation(AttestationPolicy policy) {
        return submit(() -> client.verifyGatewayAttestation(policy));
    }

    /** Closes the underlying client: one bounded telemetry flush, then its worker stops. */
    public void close() {
        client.close();
    }

    private <T> CompletableFuture<T> submit(CheckedSupplier<T> supplier) {
        Transport.CancellationToken token = new Transport.CancellationToken();
        CancellableFuture<T> future = new CancellableFuture<T>(token);
        executor.execute(() -> {
            if (future.isCancelled()) {
                return;
            }
            future.setRunner(Thread.currentThread());
            Transport.bindCancellation(token);
            try {
                future.complete(supplier.get());
            } catch (Exception error) {
                future.completeExceptionally(new CompletionException(error));
            } finally {
                // Transport returns as soon as response headers arrive, but
                // buffered endpoint suppliers decode the body afterwards.
                // Release the physical Call only after that entire supplier
                // is done so future.cancel() can still close a stalled body.
                token.clear();
                Transport.clearCancellation();
                future.clearRunner();
            }
        });
        return future;
    }

    private static final class CancellableFuture<T> extends CompletableFuture<T> {
        private final Transport.CancellationToken token;
        private volatile Thread runner;

        private CancellableFuture(Transport.CancellationToken token) {
            this.token = token;
        }

        void setRunner(Thread value) { runner = value; }
        void clearRunner() { runner = null; }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                token.cancel();
                Thread active = runner;
                if (mayInterruptIfRunning && active != null) {
                    active.interrupt();
                }
            }
            return cancelled;
        }
    }

    private interface CheckedSupplier<T> { T get() throws Exception; }
}
