package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustedrouter.models.TrustRelease;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

final class MetadataAndCompatibilityTest {
    @Test void metadataUrlsAreConfigurableAndNeverReceiveSdkCredentials() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("{\"state\":\"up\"}"));
            server.enqueue(json("{\"image_digest\":\"sha256:abc\","
                    + "\"image_reference\":\"image:release\",\"source_commit\":\"abc123\"}"));
            TrustedRouterClient client = new TrustedRouterClient(TrustedRouterOptions.builder()
                    .apiKey("sk-secret")
                    .workspaceId("ws-secret")
                    .header("Authorization", "Bearer header-secret")
                    .header("Cookie", "session=secret")
                    .header("X-Api-Key", "other-secret")
                    .header("X-Custom-Secret", "custom-secret")
                    .statusUrl(server.url("/status.json").toString())
                    .trustReleaseUrl(server.url("/release.json").toString())
                    .maxRetries(0)
                    .build());

            assertThat(client.status().get("state").getAsString()).isEqualTo("up");
            TrustRelease release = client.trustRelease();
            assertThat(release.getImageDigest()).isEqualTo("sha256:abc");
            for (int i = 0; i < 2; i++) {
                RecordedRequest request = server.takeRequest();
                assertThat(request.getHeader("Authorization")).isNull();
                assertThat(request.getHeader("Cookie")).isNull();
                assertThat(request.getHeader("X-Api-Key")).isNull();
                assertThat(request.getHeader("X-Custom-Secret")).isNull();
                assertThat(request.getHeader("X-TrustedRouter-Workspace")).isNull();
            }
        }
    }

    @Test void productionClassesTargetJava8BytecodeForAndroidCompatibility() throws Exception {
        try (InputStream resource = TrustedRouterClient.class
                .getResourceAsStream("TrustedRouterClient.class")) {
            assertThat(resource).isNotNull();
            DataInputStream input = new DataInputStream(resource);
            assertThat(input.readInt()).isEqualTo(0xCAFEBABE);
            input.readUnsignedShort();
            assertThat(input.readUnsignedShort()).isEqualTo(52);
        }
    }

    @Test void androidMinificationRulesPreserveGsonWireModels() throws Exception {
        try (InputStream resource = TrustedRouterClient.class.getClassLoader()
                .getResourceAsStream("META-INF/proguard/trusted-router.pro")) {
            assertThat(resource).isNotNull();
            Scanner scanner = new Scanner(resource, StandardCharsets.UTF_8.name())
                    .useDelimiter("\\A");
            String rules = scanner.hasNext() ? scanner.next() : "";
            assertThat(rules).contains("com.trustedrouter.models.**")
                    .contains("com.trustedrouter.oauth.OAuthToken")
                    .contains("<fields>");
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
