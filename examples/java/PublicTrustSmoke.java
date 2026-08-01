import com.google.gson.JsonObject;
import com.trustedrouter.TrustedRouterClient;
import com.trustedrouter.TrustedRouterOptions;
import com.trustedrouter.attestation.AttestationPolicy;
import com.trustedrouter.attestation.GatewayAttestation;
import com.trustedrouter.models.TrustRelease;

/** Credential-free production smoke for public status, release, and attestation metadata. */
public final class PublicTrustSmoke {
    private PublicTrustSmoke() {}

    public static void main(String[] args) throws Exception {
        TrustedRouterClient client = new TrustedRouterClient(
                TrustedRouterOptions.builder().timeoutMillis(30_000L).build());
        JsonObject status = client.status();
        JsonObject statusData = status.has("data") && status.get("data").isJsonObject()
                ? status.getAsJsonObject("data") : status;
        if (!statusData.has("generated_at") && !statusData.has("updated_at")) {
            throw new IllegalStateException("status response has no update timestamp");
        }

        TrustRelease release = client.trustRelease();
        if (release.getImageDigest() == null || release.getImageDigest().isEmpty()) {
            throw new IllegalStateException("trust release has no image digest");
        }

        GatewayAttestation attestation = client.verifyGatewayAttestation(
                AttestationPolicy.fromTrustRelease(release));
        System.out.println("status=ok");
        System.out.println("attestation=verified");
        System.out.println("image_digest=" + attestation.getImageDigest());
    }
}
