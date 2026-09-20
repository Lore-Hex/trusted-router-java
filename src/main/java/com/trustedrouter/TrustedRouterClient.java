package com.trustedrouter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trustedrouter.attestation.AttestationPolicy;
import com.trustedrouter.attestation.GatewayAttestation;
import com.trustedrouter.errors.InternalException;
import com.trustedrouter.errors.TrustedRouterException;
import com.trustedrouter.internal.AttestationHttp;
import com.trustedrouter.internal.RequestFactory;
import com.trustedrouter.internal.ResponseInputStream;
import com.trustedrouter.internal.Transport;
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
import com.trustedrouter.models.ModelDecoder;
import com.trustedrouter.models.ModelList;
import com.trustedrouter.models.ProviderList;
import com.trustedrouter.models.RegionList;
import com.trustedrouter.models.ResponseEvent;
import com.trustedrouter.models.ResponseInputTokens;
import com.trustedrouter.models.ResponseObject;
import com.trustedrouter.models.TrustRelease;
import com.trustedrouter.models.UserInfoResponse;
import com.trustedrouter.oauth.OAuth;
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
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.util.Map;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Thread-safe TrustedRouter client for Java, Kotlin, and Android.
 *
 * <p>{@link #close()} flushes pending client telemetry once (bounded by 2 s)
 * and stops its background worker; a JVM shutdown hook does the same for
 * clients that were never closed. Closing does not affect the OkHttp client,
 * which the caller may own.
 */
public final class TrustedRouterClient implements Closeable {
    private final TrustedRouterOptions options;
    private final Transport transport;

    /**
     * Creates a client using the supplied credentials or configuration.
     *
     * @param apiKey the API key used for authenticated requests
     */
    public TrustedRouterClient(String apiKey) {
        this(TrustedRouterOptions.builder().apiKey(apiKey).build());
    }

    /**
     * Creates a client using the supplied credentials or configuration.
     *
     * @param options the request or client configuration
     */
    public TrustedRouterClient(TrustedRouterOptions options) {
        if (options == null) { throw new NullPointerException("options"); }
        this.options = options;
        this.transport = new Transport(options);
    }

    /**
     * Returns base url.
     *
     * @return the base url
     */
    public String getBaseUrl() { return transport.getBaseUrl(); }
    /**
     * Returns control base url.
     *
     * @return the control base url
     */
    public String getControlBaseUrl() { return transport.getControlBaseUrl(); }
    /**
     * Creates an asynchronous facade using the configured executor.
     *
     * @return an asynchronous client sharing this client
     */
    public TrustedRouterAsyncClient async() { return new TrustedRouterAsyncClient(this, options.getAsyncExecutor()); }

    /**
     * Flushes pending client telemetry once, bounded by 2 s, and stops the
     * telemetry worker. Safe to call more than once; later requests still
     * work but are no longer recorded.
     */
    @Override
    public void close() {
        transport.close();
    }

    /** The engine, for tests in this package. */
    Transport transport() {
        return transport;
    }

    /**
     * Sends an arbitrary inference-plane request and returns parsed JSON.
     *
     * @param method the method
     * @param path the relative API path; absolute URLs are rejected
     * @param body the body
     * @param options the request or client configuration
     * @return the request
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public JsonElement request(String method, String path, JsonElement body, CallOptions options)
            throws TrustedRouterException {
        return json(Transport.Plane.INFERENCE, method, path, body, options);
    }

    /**
     * Sends an arbitrary control-plane request and returns parsed JSON.
     *
     * @param method the method
     * @param path the relative API path; absolute URLs are rejected
     * @param body the body
     * @param options the request or client configuration
     * @return the control request
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public JsonElement controlRequest(
            String method, String path, JsonElement body, CallOptions options)
            throws TrustedRouterException {
        return json(Transport.Plane.CONTROL, method, path, body, options);
    }

    /**
     * Sends an arbitrary inference-plane request and leaves the response open for the caller.
     *
     * @param method the method
     * @param path the relative API path; absolute URLs are rejected
     * @param body the body
     * @param options the request or client configuration
     * @return the open HTTP response; the caller must close it
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public Response rawRequest(String method, String path, JsonElement body, CallOptions options)
            throws TrustedRouterException {
        return transport.execute(Transport.Plane.INFERENCE, method, path, body, options, false);
    }

    /**
     * Sends an arbitrary control-plane request and leaves the response open for the caller.
     *
     * @param method the method
     * @param path the relative API path; absolute URLs are rejected
     * @param body the body
     * @param options the request or client configuration
     * @return the open HTTP response; the caller must close it
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public Response rawControlRequest(
            String method, String path, JsonElement body, CallOptions options)
            throws TrustedRouterException {
        return transport.execute(Transport.Plane.CONTROL, method, path, body, options, false);
    }

    /**
     * Requests a non-streaming chat completion on the inference plane.
     *
     * @param request the request body and per-call options
     * @return the decoded completion
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public ChatCompletion chatCompletions(ChatRequest request) throws TrustedRouterException {
        JsonElement json = json(
                Transport.Plane.INFERENCE, "POST", "/chat/completions", request.toJson(false),
                idempotent(request.getCallOptions()));
        return decodeResponse(json, ChatCompletion.class);
    }

    /**
     * Opens a stream of typed chat chunks; the caller must close it.
     *
     * @param request the request body and per-call options
     * @return the closeable chunk stream
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public EventStream<ChatCompletionChunk> chatCompletionsChunks(ChatRequest request)
            throws TrustedRouterException {
        Transport.OpenedStream opened = transport.executeStream(
                Transport.Plane.INFERENCE, "POST", "/chat/completions", request.toJson(true),
                idempotent(request.getCallOptions()));
        Response response = opened.response();
        Transport.requireSuccess(response);
        try {
            return new EventStream<ChatCompletionChunk>(response,
                    (event, data) -> decodeResponse(data, ChatCompletionChunk.class),
                    opened.recorder());
        } catch (IOException error) {
            response.close();
            throw new InternalException(502, error.getMessage(), null, error);
        }
    }

    /**
     * Opens a stream of chat text deltas; the caller must close it.
     *
     * @param request the request body and per-call options
     * @return the closeable text stream
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public TextStream chatCompletionsText(ChatRequest request) throws TrustedRouterException {
        return new TextStream(chatCompletionsChunks(request));
    }

    /**
     * Opens the raw chat SSE body; the caller must close it.
     *
     * @param request the request body and per-call options
     * @return the response body stream
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public InputStream chatCompletionsRawStream(ChatRequest request) throws TrustedRouterException {
        Transport.OpenedStream opened = transport.executeStream(
                Transport.Plane.INFERENCE, "POST", "/chat/completions", request.toJson(true),
                idempotent(request.getCallOptions()));
        return rawStream(opened);
    }

    /**
     * Convenience alias for a chat request configured with a Fusion/Synth tool.
     *
     * @param request the request body and per-call options
     * @return the fusion
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public ChatCompletion fusion(ChatRequest request) throws TrustedRouterException {
        return chatCompletions(request);
    }

    /**
     * Runs a first-class Synth/Fusion request with the orchestration timeout default.
     *
     * @param request the request body and per-call options
     * @return the fusion
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public ChatCompletion fusion(FusionRequest request) throws TrustedRouterException {
        return chatCompletions(request.toChatRequest());
    }

    /**
     * Preferred product-name alias for {@link #fusion(FusionRequest)}.
     *
     * @param request the request body and per-call options
     * @return the synth
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public ChatCompletion synth(FusionRequest request) throws TrustedRouterException {
        return fusion(request);
    }

    /**
     * Lists models matching the supplied filters, or all models when filters are absent.
     *
     * @return the model catalog
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public ModelList models() throws TrustedRouterException { return models(null); }
    /**
     * Lists models matching the supplied filters, or all models when filters are absent.
     *
     * @param filters the filters
     * @return the model catalog
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public ModelList models(ModelFilters filters) throws TrustedRouterException {
        String path = "/models";
        if (filters != null) {
            StringBuilder query = new StringBuilder();
            appendQuery(query, "open_weights", filters.getOpenWeights());
            appendQuery(query, "provider[jurisdiction]", filters.getProviderJurisdiction());
            appendQuery(query, "provider[region]", filters.getProviderRegion());
            if (query.length() > 0) { path += "?" + query; }
        }
        return decodeResponse(json(Transport.Plane.CONTROL, "GET", path, null, null), ModelList.class);
    }

    /**
     * Lists available inference providers.
     *
     * @return the provider catalog
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public ProviderList providers() throws TrustedRouterException {
        return decodeResponse(
                json(Transport.Plane.CONTROL, "GET", "/providers", null, null), ProviderList.class);
    }

    /**
     * Lists available routing regions.
     *
     * @return the region catalog
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public RegionList regions() throws TrustedRouterException {
        return decodeResponse(
                json(Transport.Plane.CONTROL, "GET", "/regions", null, null), RegionList.class);
    }

    /**
     * Reads the authenticated account credit balance.
     *
     * @return the credit balance
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public CreditsBalance credits() throws TrustedRouterException { return credits(null); }
    /**
     * Reads the authenticated account credit balance.
     *
     * @param options the request or client configuration
     * @return the credit balance
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public CreditsBalance credits(CallOptions options) throws TrustedRouterException {
        return decodeResponse(
                json(Transport.Plane.CONTROL, "GET", "/credits", null, options), CreditsBalance.class);
    }

    /**
     * Requests embeddings on the inference plane.
     *
     * @param request the request body and per-call options
     * @return the decoded embeddings
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public EmbeddingResponse embeddings(EmbeddingsRequest request) throws TrustedRouterException {
        return decodeResponse(
                json(Transport.Plane.INFERENCE, "POST", "/embeddings", request.toJson(),
                        idempotent(request.getCallOptions())), EmbeddingResponse.class);
    }

    /**
     * Sends an Anthropic-compatible Messages request.
     *
     * @param request the request body and per-call options
     * @return the decoded message
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public MessagesResponse messages(MessagesRequest request) throws TrustedRouterException {
        return decodeResponse(
                json(Transport.Plane.INFERENCE, "POST", "/messages", request.toJson(),
                        idempotent(request.getCallOptions())), MessagesResponse.class);
    }

    /**
     * Sends a non-streaming Responses API request.
     *
     * @param request the request body and per-call options
     * @return the decoded response
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public ResponseObject responses(ResponsesRequest request) throws TrustedRouterException {
        return decodeResponse(
                json(Transport.Plane.INFERENCE, "POST", "/responses", request.toJson(false),
                        idempotent(request.getCallOptions())), ResponseObject.class);
    }

    /**
     * Opens typed Responses SSE events; the caller must close the stream.
     *
     * @param request the request body and per-call options
     * @return the closeable event stream
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public EventStream<ResponseEvent> responsesEvents(ResponsesRequest request)
            throws TrustedRouterException {
        Transport.OpenedStream opened = transport.executeStream(
                Transport.Plane.INFERENCE, "POST", "/responses", request.toJson(true),
                idempotent(request.getCallOptions()));
        Response response = opened.response();
        Transport.requireSuccess(response);
        try {
            return new EventStream<ResponseEvent>(response, (event, data) -> {
                String eventName = event;
                if ((eventName == null || eventName.isEmpty()) && data.has("type")) {
                    try {
                        eventName = com.trustedrouter.internal.WireShape.string(data.get("type"));
                    } catch (com.trustedrouter.errors.InvalidResponseException error) {
                        throw new InternalException(502, error.getMessage(), data, error);
                    }
                }
                return new ResponseEvent(eventName, data);
            }, opened.recorder());
        } catch (IOException error) {
            response.close();
            throw new InternalException(502, error.getMessage(), null, error);
        }
    }

    /**
     * Opens the raw Responses SSE body; the caller must close it.
     *
     * @param request the request body and per-call options
     * @return the response body stream
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public InputStream responsesRawStream(ResponsesRequest request) throws TrustedRouterException {
        Transport.OpenedStream opened = transport.executeStream(
                Transport.Plane.INFERENCE, "POST", "/responses", request.toJson(true),
                idempotent(request.getCallOptions()));
        return rawStream(opened);
    }

    private static InputStream rawStream(Transport.OpenedStream opened)
            throws TrustedRouterException {
        Response response = opened.response();
        Transport.requireSuccess(response);
        ResponseBody body = response.body();
        if (body == null) {
            response.close();
            InternalException failure =
                    new InternalException(502, "TrustedRouter stream had no body", null);
            opened.abandon(failure);
            throw failure;
        }
        return new ResponseInputStream(response, body.byteStream(), opened.recorder());
    }

    /**
     * Counts input tokens without creating a stored response.
     *
     * @param request the request body and per-call options
     * @return the input token count
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public ResponseInputTokens responsesInputTokens(ResponsesRequest request)
            throws TrustedRouterException {
        return decodeResponse(
                json(Transport.Plane.INFERENCE, "POST", "/responses/input_tokens",
                        request.toJson(false), idempotent(request.getCallOptions())),
                ResponseInputTokens.class);
    }

    /**
     * Lists configured Broadcast destinations on the control plane.
     *
     * @param options the request or client configuration
     * @return the destination list
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public BroadcastDestinationList broadcastDestinations(CallOptions options)
            throws TrustedRouterException {
        return decodeResponse(
                json(Transport.Plane.CONTROL, "GET", "/broadcast/destinations", null, options),
                BroadcastDestinationList.class);
    }
    /**
     * Lists configured Broadcast destinations on the control plane.
     *
     * @return the destination list
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public BroadcastDestinationList broadcastDestinations() throws TrustedRouterException {
        return broadcastDestinations(null);
    }

    /**
     * Creates a Broadcast destination on the control plane.
     *
     * @param request the request body and per-call options
     * @return the created destination
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public BroadcastDestination createBroadcastDestination(BroadcastDestinationRequest request)
            throws TrustedRouterException {
        return decodeResponse(
                json(Transport.Plane.CONTROL, "POST", "/broadcast/destinations", request.toJson(),
                        idempotent(request.getCallOptions())), BroadcastDestination.class);
    }

    /**
     * Returns broadcast destination.
     *
     * @param id the id
     * @param options the request or client configuration
     * @return the broadcast destination
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public BroadcastDestination getBroadcastDestination(String id, CallOptions options)
            throws TrustedRouterException {
        return decodeResponse(
                json(Transport.Plane.CONTROL, "GET", destinationPath(id), null, options),
                BroadcastDestination.class);
    }
    /**
     * Returns broadcast destination.
     *
     * @param id the id
     * @return the broadcast destination
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public BroadcastDestination getBroadcastDestination(String id) throws TrustedRouterException {
        return getBroadcastDestination(id, null);
    }

    /**
     * Updates a Broadcast destination on the control plane.
     *
     * @param id the id
     * @param patch the patch
     * @param options the request or client configuration
     * @return the updated destination
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public BroadcastDestination updateBroadcastDestination(
            String id, JsonObject patch, CallOptions options) throws TrustedRouterException {
        return decodeResponse(
                json(Transport.Plane.CONTROL, "PATCH", destinationPath(id), patch,
                        idempotent(options)),
                BroadcastDestination.class);
    }

    /**
     * Deletes the identified Broadcast destination.
     *
     * @param id the id
     * @param options the request or client configuration
     * @return the deletion response
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public JsonElement deleteBroadcastDestination(String id, CallOptions options)
            throws TrustedRouterException {
        return json(Transport.Plane.CONTROL, "DELETE", destinationPath(id), null,
                idempotent(options));
    }
    /**
     * Deletes the identified Broadcast destination.
     *
     * @param id the id
     * @return the deletion response
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public JsonElement deleteBroadcastDestination(String id) throws TrustedRouterException {
        return deleteBroadcastDestination(id, null);
    }

    /**
     * Sends a test event to the identified Broadcast destination.
     *
     * @param id the id
     * @param options the request or client configuration
     * @return the test response
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public JsonElement testBroadcastDestination(String id, CallOptions options)
            throws TrustedRouterException {
        return json(Transport.Plane.CONTROL, "POST", destinationPath(id) + "/test", null,
                idempotent(options));
    }
    /**
     * Sends a test event to the identified Broadcast destination.
     *
     * @param id the id
     * @return the test response
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public JsonElement testBroadcastDestination(String id) throws TrustedRouterException {
        return testBroadcastDestination(id, null);
    }

    /**
     * Creates a billing checkout using a decimal-string amount.
     *
     * @param request the request body and per-call options
     * @return the checkout details
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public CheckoutResponse billingCheckout(BillingCheckoutRequest request)
            throws TrustedRouterException {
        return decodeResponse(
                json(Transport.Plane.CONTROL, "POST", "/billing/checkout", request.toJson(),
                        idempotent(request.getCallOptions())), CheckoutResponse.class);
    }

    /**
     * Creates a stablecoin checkout using a decimal-string amount.
     *
     * @param request the request body and per-call options
     * @return the checkout details
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public CheckoutResponse stablecoinCheckout(BillingCheckoutRequest request)
            throws TrustedRouterException {
        JsonObject body = request.toJson();
        body.addProperty("payment_method", "stablecoin");
        return decodeResponse(
                json(Transport.Plane.CONTROL, "POST", "/billing/checkout", body,
                        idempotent(request.getCallOptions())), CheckoutResponse.class);
    }

    /**
     * Reads the current authenticated session.
     *
     * @return the session details
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public AuthSessionResponse authSession() throws TrustedRouterException {
        return decodeResponse(
                json(Transport.Plane.CONTROL, "GET", "/auth/session", null, null),
                AuthSessionResponse.class);
    }

    /**
     * Logs out the current authenticated session.
     *
     * @return the logout response
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public LogoutResponse logout() throws TrustedRouterException {
        return decodeResponse(
                json(Transport.Plane.CONTROL, "POST", "/auth/logout", null,
                        idempotent(null)),
                LogoutResponse.class);
    }

    /**
     * Reads the authenticated user profile.
     *
     * @return the user profile
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public UserInfoResponse userInfo() throws TrustedRouterException {
        return decodeResponse(
                json(Transport.Plane.CONTROL, "GET", "/auth/userinfo", null, null),
                UserInfoResponse.class);
    }

    /**
     * Builds the OAuth authorization URL for user consent.
     *
     * @param options the request or client configuration
     * @return the authorization URL
     */
    public String oauthAuthorizeUrl(OAuthAuthorizeOptions options) {
        return OAuth.authorizeUrl(transport.getControlBaseUrl(), options);
    }

    /**
     * Creates an OAuth authorization URL with state and PKCE material to retain for the callback.
     *
     * @param options the request or client configuration
     * @return the authorization and callback verification material
     */
    public OAuthAuthorization createOAuthAuthorization(OAuthAuthorizeOptions options) {
        return OAuth.createAuthorization(transport.getControlBaseUrl(), options, null);
    }

    /**
     * Creates an OAuth authorization URL with state and PKCE material to retain for the callback.
     *
     * @param options the request or client configuration
     * @param codeVerifier the code verifier
     * @return the authorization and callback verification material
     */
    public OAuthAuthorization createOAuthAuthorization(
            OAuthAuthorizeOptions options, String codeVerifier) {
        return OAuth.createAuthorization(transport.getControlBaseUrl(), options, codeVerifier);
    }

    /**
     * Exchanges a one-time authorization code without sending the client's bearer key.
     *
     * @param code the code
     * @param codeVerifier the code verifier
     * @param codeChallengeMethod the code challenge method
     * @return the exchange oauth key
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public OAuthToken exchangeOAuthKey(
            String code, String codeVerifier, String codeChallengeMethod)
            throws TrustedRouterException {
        if (code == null || code.isEmpty()) { throw new IllegalArgumentException("code is required"); }
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        if (codeVerifier != null && !codeVerifier.isEmpty()) {
            body.addProperty("code_verifier", codeVerifier);
        }
        if (codeChallengeMethod != null && !codeChallengeMethod.isEmpty()) {
            body.addProperty("code_challenge_method", codeChallengeMethod);
        }
        return decodeResponse(
                Transport.decodeJson(transport.executeCredentialFreeControl(
                        "POST", "/auth/keys", body, false)), OAuthToken.class);
    }

    /**
     * Reads account activity from the control plane.
     *
     * @param parameters the parameters
     * @return the activity response
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public ActivityResponse activity(Map<String, String> parameters) throws TrustedRouterException {
        StringBuilder query = new StringBuilder();
        if (parameters != null) {
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                appendQuery(query, entry.getKey(), entry.getValue());
            }
        }
        String path = query.length() == 0 ? "/activity" : "/activity?" + query;
        return decodeResponse(
                json(Transport.Plane.CONTROL, "GET", path, null, null), ActivityResponse.class);
    }

    /**
     * Reads account activity from the control plane.
     *
     * @return the activity response
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public ActivityResponse activity() throws TrustedRouterException { return activity(null); }

    /**
     * Fetches public service status without sending credentials.
     *
     * @return the status JSON
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public JsonObject status() throws TrustedRouterException {
        return status(options.getStatusUrl());
    }

    /**
     * Fetches public service status without sending credentials.
     *
     * @param url the url
     * @return the status JSON
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public JsonObject status(String url) throws TrustedRouterException {
        JsonElement value = absoluteJson(url);
        try {
            return com.trustedrouter.internal.WireShape.object(value);
        } catch (com.trustedrouter.errors.InvalidResponseException error) {
            throw new InternalException(502, error.getMessage(), value, error);
        }
    }

    /**
     * Fetches gateway attestation evidence bound to the supplied or generated nonce.
     *
     * @return the attestation document bytes
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public byte[] attestation() throws TrustedRouterException { return attestation(null); }
    /**
     * Fetches gateway attestation evidence bound to the supplied or generated nonce.
     *
     * @param nonceHex the nonce hex
     * @return the attestation document bytes
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public byte[] attestation(String nonceHex) throws TrustedRouterException {
        return AttestationHttp.fetchAttestation(transport, transport.getBaseUrl(), nonceHex);
    }

    /**
     * Fetches and verifies a fresh attestation against the TLS leaf certificate from the
     * exact OkHttp connection that returned the JWT.
     *
     * @param policy the policy
     * @return the verified attestation
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     * @throws GeneralSecurityException if the operation cannot be completed
     */
    public GatewayAttestation verifyGatewayAttestation(AttestationPolicy policy)
            throws TrustedRouterException, GeneralSecurityException {
        return verifyGatewayAttestation(policy, AttestationHttp.randomNonceHex());
    }

    /**
     * Fetches and verifies gateway attestation against the supplied policy.
     *
     * @param policy the policy
     * @param nonceHex the nonce hex
     * @return the verified attestation
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     * @throws GeneralSecurityException if the operation cannot be completed
     */
    public GatewayAttestation verifyGatewayAttestation(AttestationPolicy policy, String nonceHex)
            throws TrustedRouterException, GeneralSecurityException {
        return AttestationHttp.verifyGatewayAttestation(
                transport, transport.getBaseUrl(), policy, nonceHex);
    }

    /**
     * Fetches public trust release metadata without sending credentials.
     *
     * @return the trust release
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public TrustRelease trustRelease() throws TrustedRouterException {
        return trustRelease(options.getTrustReleaseUrl());
    }

    /**
     * Fetches public trust release metadata without sending credentials.
     *
     * @param url the url
     * @return the trust release
     * @throws TrustedRouterException if the request or response fails validation, or the service returns an error
     */
    public TrustRelease trustRelease(String url) throws TrustedRouterException {
        return decodeResponse(
                absoluteJson(url), TrustRelease.class);
    }

    private JsonElement json(
            Transport.Plane plane, String method, String path, JsonElement body, CallOptions options)
            throws TrustedRouterException {
        return Transport.decodeJson(transport.execute(plane, method, path, body, options, false));
    }

    private static <T extends com.trustedrouter.models.JsonModel> T decodeResponse(
            JsonElement json, Class<T> type) throws InternalException {
        try {
            return ModelDecoder.decode(json, type);
        } catch (com.trustedrouter.errors.InvalidResponseException error) {
            throw new InternalException(502, error.getMessage(), json, error);
        }
    }

    private JsonElement absoluteJson(String url) throws TrustedRouterException {
        return Transport.decodeJson(transport.executeAbsolute(url, "GET", false));
    }

    private static CallOptions idempotent(CallOptions options) {
        // Minted once per logical call, BEFORE the transport loop, so every
        // attempt and every domain move replays the same key verbatim.
        return RequestFactory.ensureIdempotencyKey(options);
    }

    private static String destinationPath(String id) {
        if (id == null || id.isEmpty() || id.contains("/") || id.contains("..")) {
            throw new IllegalArgumentException("invalid destination id");
        }
        return "/broadcast/destinations/" + encode(id);
    }

    private static void appendQuery(StringBuilder query, String key, Object value) {
        if (value == null) { return; }
        if (query.length() > 0) { query.append('&'); }
        query.append(encode(key)).append('=').append(encode(String.valueOf(value)));
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 unavailable", impossible);
        }
    }
}
