package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.trustedrouter.oauth.OAuth;
import com.trustedrouter.oauth.OAuthAuthorization;
import com.trustedrouter.oauth.OAuthAuthorizeOptions;
import com.trustedrouter.oauth.OAuthCallback;
import com.trustedrouter.oauth.OAuthPkcePair;
import com.trustedrouter.oauth.OAuthToken;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

final class OAuthTest {
    @Test void pkceMatchesRfc7636Vector() {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        OAuthPkcePair pair = OAuth.createPkcePair(verifier);
        assertThat(pair.getCodeChallenge()).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
        assertThat(pair.getCodeChallengeMethod()).isEqualTo("S256");
    }

    @Test void authorizationEmbedsStateInCustomSchemeCallback() {
        OAuthAuthorizeOptions options = OAuthAuthorizeOptions.builder("myapp://oauth/callback?x=1")
                .keyLabel("Android app").limit("5.000001").usageLimitType("monthly")
                .state("state value").build();
        OAuthAuthorization auth = OAuth.createAuthorization(
                "https://trustedrouter.com/v1", options,
                "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");
        HttpUrl url = HttpUrl.get(auth.getUrl());
        assertThat(url.encodedPath()).isEqualTo("/v1/auth");
        assertThat(url.queryParameter("callback_url"))
                .isEqualTo("myapp://oauth/callback?x=1&state=state%20value");
        assertThat(url.queryParameter("limit")).isEqualTo("5.000001");
        assertThat(auth.getState()).isEqualTo("state value");
    }

    @Test void authorizationReplacesExistingStateAndPreservesFragment() {
        OAuthAuthorizeOptions options = OAuthAuthorizeOptions.builder(
                "myapp://oauth/callback?x=a%20b&state=old#complete")
                .state("new value")
                .build();
        OAuthAuthorization auth = OAuth.createAuthorization(
                "https://trustedrouter.com/v1", options,
                "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");
        String callback = HttpUrl.get(auth.getUrl()).queryParameter("callback_url");
        assertThat(callback)
                .isEqualTo("myapp://oauth/callback?x=a%20b&state=new%20value#complete");
    }

    @Test void exchangeSuppressesConfiguredBearerAndReturnsDelegatedKey() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"key\":\"delegated-test-key\",\"user_id\":\"u1\","
                            + "\"identity\":{\"sub\":\"u1\"}}"));
            TrustedRouterClient client = new TrustedRouterClient(TrustedRouterOptions.builder()
                    .apiKey("sk-owner")
                    .header("Authorization", "Bearer header-owner")
                    .baseUrl(server.url("/v1").toString())
                    .controlBaseUrl(server.url("/v1").toString()).maxRetries(0).build());

            OAuthToken token = client.exchangeOAuthKey("code", "verifier", "S256");
            assertThat(token.getKey()).isEqualTo("delegated-test-key");
            assertThat(token.getIdentity().get("sub").getAsString()).isEqualTo("u1");
            RecordedRequest request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/v1/auth/keys");
            assertThat(request.getHeader("Authorization")).isNull();
            JsonObject body = com.trustedrouter.internal.JsonSupport.parse(
                    request.getBody().readUtf8()).getAsJsonObject();
            assertThat(body.get("code_verifier").getAsString()).isEqualTo("verifier");
        }
    }

    @Test void invalidPkceAndAuthorizeInputsFailLocally() {
        assertThatThrownBy(() -> OAuth.createPkcePair("short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OAuth.createPkcePair(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa+aa"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid characters");
        OAuthAuthorizeOptions missingChallenge = OAuthAuthorizeOptions.builder("https://app/cb")
                .codeChallengeMethod("S256").build();
        assertThatThrownBy(() -> OAuth.authorizeUrl("https://trustedrouter.com/v1", missingChallenge))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void androidCallbackIsStateValidatedBeforeCodeExchange() {
        OAuthCallback callback = OAuth.parseCallback(
                "trustedrouter-demo://oauth/callback?code=one%2Dtime&state=safe%20state",
                "safe state");
        assertThat(callback.getCode()).isEqualTo("one-time");

        assertThatThrownBy(() -> OAuth.parseCallback(
                "trustedrouter-demo://oauth/callback?code=stolen&state=wrong", "safe state"))
                .hasMessageContaining("state mismatch");
        assertThatThrownBy(() -> OAuth.parseCallback(
                "trustedrouter-demo://oauth/callback?error=access_denied&"
                        + "error_description=User%20cancelled&state=safe%20state", "safe state"))
                .hasMessageContaining("User cancelled");
        assertThatThrownBy(() -> OAuth.parseCallback(
                "trustedrouter-demo://oauth/callback?state=safe%20state", "safe state"))
                .hasMessageContaining("missing code");
        assertThatThrownBy(() -> OAuth.parseCallback(
                "trustedrouter-demo://oauth/callback?code=one&state=safe%20state&state=other",
                "safe state"))
                .hasMessageContaining("duplicate state");
    }
}
