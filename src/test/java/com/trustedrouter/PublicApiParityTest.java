package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class PublicApiParityTest {
    @Test void synchronousClientRetainsSiblingSdkSurface() {
        assertMethods(TrustedRouterClient.class,
                "request", "rawRequest", "controlRequest", "rawControlRequest",
                "chatCompletions", "chatCompletionsChunks", "chatCompletionsText",
                "chatCompletionsRawStream", "fusion", "synth", "models", "providers",
                "regions", "credits", "embeddings", "messages", "responses",
                "responsesEvents", "responsesRawStream", "responsesInputTokens",
                "broadcastDestinations", "createBroadcastDestination",
                "getBroadcastDestination", "updateBroadcastDestination",
                "deleteBroadcastDestination", "testBroadcastDestination", "billingCheckout",
                "stablecoinCheckout", "authSession", "logout", "userInfo", "activity",
                "oauthAuthorizeUrl", "createOAuthAuthorization", "exchangeOAuthKey",
                "status", "attestation", "verifyGatewayAttestation", "trustRelease", "async");
    }

    @Test void asyncClientRetainsAllNetworkOperationFamilies() {
        assertMethods(TrustedRouterAsyncClient.class,
                "request", "rawRequest", "controlRequest", "rawControlRequest",
                "chatCompletions", "chatCompletionsChunks", "chatCompletionsText",
                "chatCompletionsRawStream", "fusion", "synth", "models", "providers",
                "regions", "credits", "embeddings", "messages", "responses",
                "responsesEvents", "responsesRawStream", "responsesInputTokens",
                "broadcastDestinations", "createBroadcastDestination",
                "getBroadcastDestination", "updateBroadcastDestination",
                "deleteBroadcastDestination", "testBroadcastDestination", "billingCheckout",
                "stablecoinCheckout", "authSession", "logout", "userInfo", "activity",
                "exchangeOAuthKey", "status", "attestation", "verifyGatewayAttestation",
                "trustRelease");
    }

    private static void assertMethods(Class<?> type, String... expected) {
        Set<String> actual = Arrays.stream(type.getMethods())
                .map(Method::getName).collect(Collectors.toSet());
        Set<String> missing = new HashSet<String>(Arrays.asList(expected));
        missing.removeAll(actual);
        assertThat(missing).isEmpty();
    }
}
