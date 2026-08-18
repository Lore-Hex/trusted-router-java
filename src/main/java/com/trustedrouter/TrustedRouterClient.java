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
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.util.Map;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Thread-safe TrustedRouter client for Java, Kotlin, and Android. */
public final class TrustedRouterClient {
    private final TrustedRouterOptions options;
    private final Transport transport;

    public TrustedRouterClient(String apiKey) {
        this(TrustedRouterOptions.builder().apiKey(apiKey).build());
    }

    public TrustedRouterClient(TrustedRouterOptions options) {
        if (options == null) { throw new NullPointerException("options"); }
        this.options = options;
        this.transport = new Transport(options);
    }

    public String getBaseUrl() { return transport.getBaseUrl(); }
    public String getControlBaseUrl() { return transport.getControlBaseUrl(); }
    public TrustedRouterAsyncClient async() { return new TrustedRouterAsyncClient(this, options.getAsyncExecutor()); }

    /** Sends an arbitrary inference-plane request and returns parsed JSON. */
    public JsonElement request(String method, String path, JsonElement body, CallOptions options)
            throws TrustedRouterException {
        return json(Transport.Plane.INFERENCE, method, path, body, options);
    }

    /** Sends an arbitrary control-plane request and returns parsed JSON. */
    public JsonElement controlRequest(
            String method, String path, JsonElement body, CallOptions options)
            throws TrustedRouterException {
        return json(Transport.Plane.CONTROL, method, path, body, options);
    }

    /** Sends an arbitrary inference-plane request and leaves the response open for the caller. */
    public Response rawRequest(String method, String path, JsonElement body, CallOptions options)
            throws TrustedRouterException {
        return transport.execute(Transport.Plane.INFERENCE, method, path, body, options, false);
    }

    /** Sends an arbitrary control-plane request and leaves the response open for the caller. */
    public Response rawControlRequest(
            String method, String path, JsonElement body, CallOptions options)
            throws TrustedRouterException {
        return transport.execute(Transport.Plane.CONTROL, method, path, body, options, false);
    }

    public ChatCompletion chatCompletions(ChatRequest request) throws TrustedRouterException {
        JsonElement json = json(
                Transport.Plane.INFERENCE, "POST", "/chat/completions", request.toJson(false),
                idempotent(request.getCallOptions()));
        return ModelDecoder.decode(json, ChatCompletion.class);
    }

    public EventStream<ChatCompletionChunk> chatCompletionsChunks(ChatRequest request)
            throws TrustedRouterException {
        Response response = transport.execute(
                Transport.Plane.INFERENCE, "POST", "/chat/completions", request.toJson(true),
                idempotent(request.getCallOptions()), true);
        Transport.requireSuccess(response);
        try {
            return new EventStream<ChatCompletionChunk>(response,
                    (event, data) -> ModelDecoder.decode(data, ChatCompletionChunk.class));
        } catch (IOException error) {
            response.close();
            throw new InternalException(502, error.getMessage(), null, error);
        }
    }

    public TextStream chatCompletionsText(ChatRequest request) throws TrustedRouterException {
        return new TextStream(chatCompletionsChunks(request));
    }

    public InputStream chatCompletionsRawStream(ChatRequest request) throws TrustedRouterException {
        Response response = transport.execute(
                Transport.Plane.INFERENCE, "POST", "/chat/completions", request.toJson(true),
                idempotent(request.getCallOptions()), true);
        Transport.requireSuccess(response);
        ResponseBody body = response.body();
        if (body == null) {
            response.close();
            throw new InternalException(502, "TrustedRouter stream had no body", null);
        }
        return new ResponseInputStream(response, body.byteStream());
    }

    /** Convenience alias for a chat request configured with a Fusion/Synth tool. */
    public ChatCompletion fusion(ChatRequest request) throws TrustedRouterException {
        return chatCompletions(request);
    }

    /** Runs a first-class Synth/Fusion request with the orchestration timeout default. */
    public ChatCompletion fusion(FusionRequest request) throws TrustedRouterException {
        return chatCompletions(request.toChatRequest());
    }

    /** Preferred product-name alias for {@link #fusion(FusionRequest)}. */
    public ChatCompletion synth(FusionRequest request) throws TrustedRouterException {
        return fusion(request);
    }

    public ModelList models() throws TrustedRouterException { return models(null); }
    public ModelList models(ModelFilters filters) throws TrustedRouterException {
        String path = "/models";
        if (filters != null) {
            StringBuilder query = new StringBuilder();
            appendQuery(query, "open_weights", filters.getOpenWeights());
            appendQuery(query, "provider[jurisdiction]", filters.getProviderJurisdiction());
            appendQuery(query, "provider[region]", filters.getProviderRegion());
            if (query.length() > 0) { path += "?" + query; }
        }
        return ModelDecoder.decode(json(Transport.Plane.CONTROL, "GET", path, null, null), ModelList.class);
    }

    public ProviderList providers() throws TrustedRouterException {
        return ModelDecoder.decode(
                json(Transport.Plane.CONTROL, "GET", "/providers", null, null), ProviderList.class);
    }

    public RegionList regions() throws TrustedRouterException {
        return ModelDecoder.decode(
                json(Transport.Plane.CONTROL, "GET", "/regions", null, null), RegionList.class);
    }

    public CreditsBalance credits() throws TrustedRouterException { return credits(null); }
    public CreditsBalance credits(CallOptions options) throws TrustedRouterException {
        return ModelDecoder.decode(
                json(Transport.Plane.CONTROL, "GET", "/credits", null, options), CreditsBalance.class);
    }

    public EmbeddingResponse embeddings(EmbeddingsRequest request) throws TrustedRouterException {
        return ModelDecoder.decode(
                json(Transport.Plane.INFERENCE, "POST", "/embeddings", request.toJson(),
                        idempotent(request.getCallOptions())), EmbeddingResponse.class);
    }

    public MessagesResponse messages(MessagesRequest request) throws TrustedRouterException {
        return ModelDecoder.decode(
                json(Transport.Plane.INFERENCE, "POST", "/messages", request.toJson(),
                        idempotent(request.getCallOptions())), MessagesResponse.class);
    }

    public ResponseObject responses(ResponsesRequest request) throws TrustedRouterException {
        return ModelDecoder.decode(
                json(Transport.Plane.INFERENCE, "POST", "/responses", request.toJson(false),
                        idempotent(request.getCallOptions())), ResponseObject.class);
    }

    public EventStream<ResponseEvent> responsesEvents(ResponsesRequest request)
            throws TrustedRouterException {
        Response response = transport.execute(
                Transport.Plane.INFERENCE, "POST", "/responses", request.toJson(true),
                idempotent(request.getCallOptions()), true);
        Transport.requireSuccess(response);
        try {
            return new EventStream<ResponseEvent>(response, (event, data) -> {
                String eventName = event;
                if ((eventName == null || eventName.isEmpty()) && data.has("type")) {
                    eventName = data.get("type").getAsString();
                }
                return new ResponseEvent(eventName, data);
            });
        } catch (IOException error) {
            response.close();
            throw new InternalException(502, error.getMessage(), null, error);
        }
    }

    public InputStream responsesRawStream(ResponsesRequest request) throws TrustedRouterException {
        Response response = transport.execute(
                Transport.Plane.INFERENCE, "POST", "/responses", request.toJson(true),
                idempotent(request.getCallOptions()), true);
        Transport.requireSuccess(response);
        ResponseBody body = response.body();
        if (body == null) {
            response.close();
            throw new InternalException(502, "TrustedRouter stream had no body", null);
        }
        return new ResponseInputStream(response, body.byteStream());
    }

    public ResponseInputTokens responsesInputTokens(ResponsesRequest request)
            throws TrustedRouterException {
        return ModelDecoder.decode(
                json(Transport.Plane.INFERENCE, "POST", "/responses/input_tokens",
                        request.toJson(false), idempotent(request.getCallOptions())),
                ResponseInputTokens.class);
    }

    public BroadcastDestinationList broadcastDestinations(CallOptions options)
            throws TrustedRouterException {
        return ModelDecoder.decode(
                json(Transport.Plane.CONTROL, "GET", "/broadcast/destinations", null, options),
                BroadcastDestinationList.class);
    }
    public BroadcastDestinationList broadcastDestinations() throws TrustedRouterException {
        return broadcastDestinations(null);
    }

    public BroadcastDestination createBroadcastDestination(BroadcastDestinationRequest request)
            throws TrustedRouterException {
        return ModelDecoder.decode(
                json(Transport.Plane.CONTROL, "POST", "/broadcast/destinations", request.toJson(),
                        idempotent(request.getCallOptions())), BroadcastDestination.class);
    }

    public BroadcastDestination getBroadcastDestination(String id, CallOptions options)
            throws TrustedRouterException {
        return ModelDecoder.decode(
                json(Transport.Plane.CONTROL, "GET", destinationPath(id), null, options),
                BroadcastDestination.class);
    }
    public BroadcastDestination getBroadcastDestination(String id) throws TrustedRouterException {
        return getBroadcastDestination(id, null);
    }

    public BroadcastDestination updateBroadcastDestination(
            String id, JsonObject patch, CallOptions options) throws TrustedRouterException {
        return ModelDecoder.decode(
                json(Transport.Plane.CONTROL, "PATCH", destinationPath(id), patch,
                        idempotent(options)),
                BroadcastDestination.class);
    }

    public JsonElement deleteBroadcastDestination(String id, CallOptions options)
            throws TrustedRouterException {
        return json(Transport.Plane.CONTROL, "DELETE", destinationPath(id), null,
                idempotent(options));
    }
    public JsonElement deleteBroadcastDestination(String id) throws TrustedRouterException {
        return deleteBroadcastDestination(id, null);
    }

    public JsonElement testBroadcastDestination(String id, CallOptions options)
            throws TrustedRouterException {
        return json(Transport.Plane.CONTROL, "POST", destinationPath(id) + "/test", null,
                idempotent(options));
    }
    public JsonElement testBroadcastDestination(String id) throws TrustedRouterException {
        return testBroadcastDestination(id, null);
    }

    public CheckoutResponse billingCheckout(BillingCheckoutRequest request)
            throws TrustedRouterException {
        return ModelDecoder.decode(
                json(Transport.Plane.CONTROL, "POST", "/billing/checkout", request.toJson(),
                        idempotent(request.getCallOptions())), CheckoutResponse.class);
    }

    public CheckoutResponse stablecoinCheckout(BillingCheckoutRequest request)
            throws TrustedRouterException {
        JsonObject body = request.toJson();
        body.addProperty("payment_method", "stablecoin");
        return ModelDecoder.decode(
                json(Transport.Plane.CONTROL, "POST", "/billing/checkout", body,
                        idempotent(request.getCallOptions())), CheckoutResponse.class);
    }

    public AuthSessionResponse authSession() throws TrustedRouterException {
        return ModelDecoder.decode(
                json(Transport.Plane.CONTROL, "GET", "/auth/session", null, null),
                AuthSessionResponse.class);
    }

    public LogoutResponse logout() throws TrustedRouterException {
        return ModelDecoder.decode(
                json(Transport.Plane.CONTROL, "POST", "/auth/logout", null,
                        idempotent(null)),
                LogoutResponse.class);
    }

    public UserInfoResponse userInfo() throws TrustedRouterException {
        return ModelDecoder.decode(
                json(Transport.Plane.CONTROL, "GET", "/auth/userinfo", null, null),
                UserInfoResponse.class);
    }

    public String oauthAuthorizeUrl(OAuthAuthorizeOptions options) {
        return OAuth.authorizeUrl(transport.getControlBaseUrl(), options);
    }

    public OAuthAuthorization createOAuthAuthorization(OAuthAuthorizeOptions options) {
        return OAuth.createAuthorization(transport.getControlBaseUrl(), options, null);
    }

    public OAuthAuthorization createOAuthAuthorization(
            OAuthAuthorizeOptions options, String codeVerifier) {
        return OAuth.createAuthorization(transport.getControlBaseUrl(), options, codeVerifier);
    }

    /** Exchanges a one-time authorization code without sending the client's bearer key. */
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
        return ModelDecoder.decode(
                Transport.decodeJson(transport.executeCredentialFreeControl(
                        "POST", "/auth/keys", body, false)), OAuthToken.class);
    }

    public ActivityResponse activity(Map<String, String> parameters) throws TrustedRouterException {
        StringBuilder query = new StringBuilder();
        if (parameters != null) {
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                appendQuery(query, entry.getKey(), entry.getValue());
            }
        }
        String path = query.length() == 0 ? "/activity" : "/activity?" + query;
        return ModelDecoder.decode(
                json(Transport.Plane.CONTROL, "GET", path, null, null), ActivityResponse.class);
    }

    public ActivityResponse activity() throws TrustedRouterException { return activity(null); }

    public JsonObject status() throws TrustedRouterException {
        return status(options.getStatusUrl());
    }

    public JsonObject status(String url) throws TrustedRouterException {
        return absoluteJson(url).getAsJsonObject();
    }

    public byte[] attestation() throws TrustedRouterException { return attestation(null); }
    public byte[] attestation(String nonceHex) throws TrustedRouterException {
        return AttestationHttp.fetchAttestation(transport, transport.getBaseUrl(), nonceHex);
    }

    /**
     * Fetches and verifies a fresh attestation against the TLS leaf certificate from the
     * exact OkHttp connection that returned the JWT.
     */
    public GatewayAttestation verifyGatewayAttestation(AttestationPolicy policy)
            throws TrustedRouterException, GeneralSecurityException {
        return verifyGatewayAttestation(policy, AttestationHttp.randomNonceHex());
    }

    public GatewayAttestation verifyGatewayAttestation(AttestationPolicy policy, String nonceHex)
            throws TrustedRouterException, GeneralSecurityException {
        return AttestationHttp.verifyGatewayAttestation(
                transport, transport.getBaseUrl(), policy, nonceHex);
    }

    public TrustRelease trustRelease() throws TrustedRouterException {
        return trustRelease(options.getTrustReleaseUrl());
    }

    public TrustRelease trustRelease(String url) throws TrustedRouterException {
        return ModelDecoder.decode(
                absoluteJson(url), TrustRelease.class);
    }

    private JsonElement json(
            Transport.Plane plane, String method, String path, JsonElement body, CallOptions options)
            throws TrustedRouterException {
        return Transport.decodeJson(transport.execute(plane, method, path, body, options, false));
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
