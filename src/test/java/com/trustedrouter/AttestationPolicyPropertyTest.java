package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.trustedrouter.attestation.AttestationPolicy;
import com.trustedrouter.internal.JsonSupport;
import com.trustedrouter.models.TrustRelease;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Property tests for the attestation policy boundary.
 *
 * <p>The law is a soundness statement about verification:
 *
 * <pre>
 * for every claims set K and policy P,
 *     verification succeeds  =&gt;  K's image identity was in P's accepted set
 * </pre>
 *
 * <p>Before the non-vacuity guard this was false, and falsifiably so.
 * {@code requireOneOf} returns immediately on an empty accepted list:
 *
 * <pre>
 * if (expected == null || expected.isEmpty()) { return; }
 * </pre>
 *
 * <p>so an empty set <em>skips</em> the check rather than failing it. And
 * {@code fromTrustRelease} mapped a release with no image fields to exactly
 * that: a truncated body, an error page that parsed as JSON, or a schema change
 * produced a policy under which both image checks silently no-op. Verification
 * then succeeded against any genuinely-attested Confidential Space workload
 * while reporting success.
 *
 * <p>These quantify over the shapes a degraded HTTP response actually takes —
 * nulls, empty strings, empty lists, and lists whose only entries are empty or
 * null — rather than pinning one example, because the defect lives in a shape
 * nobody thought to write a case for.
 *
 * <p>Mirrors {@code tests/test_attestation_properties.py} in trusted-router-py
 * and {@code test/attestation-properties.test.js} in trusted-router-js.
 */
class AttestationPolicyPropertyTest {

    private static final List<String> ABSENT_STRINGS = Arrays.asList(null, "", "   ");
    private static final List<List<String>> ABSENT_LISTS = Arrays.asList(
            null,
            Collections.<String>emptyList(),
            Collections.singletonList((String) null),
            Collections.singletonList(""));

    /** Builds a TrustRelease the way the SDK does: by deserialising wire JSON.
     *  A null field is omitted entirely, which is exactly what a truncated or
     *  schema-drifted response looks like. */
    private static TrustRelease release(
            String digest, List<String> digests, String reference, List<String> references) {
        JsonObject body = new JsonObject();
        if (digest != null) { body.addProperty("image_digest", digest); }
        if (reference != null) { body.addProperty("image_reference", reference); }
        if (digests != null) { body.add("accepted_image_digests", array(digests)); }
        if (references != null) { body.add("accepted_image_references", array(references)); }
        return JsonSupport.GSON.fromJson(body, TrustRelease.class);
    }

    private static JsonArray array(List<String> values) {
        JsonArray out = new JsonArray();
        for (String value : values) { out.add(value); }
        return out;
    }

    private static TrustRelease emptyRelease() {
        return JsonSupport.GSON.fromJson(new JsonObject(), TrustRelease.class);
    }

    // ------------------------------------------------------- non-vacuity ---

    @Test
    void aPolicyIsEitherRefusedOrPinsImageIdentity() {
        // The exhaustive product of every "absent" shape. Each combination is a
        // degraded release the builder used to turn into an unpinned policy.
        for (String digest : ABSENT_STRINGS) {
            for (List<String> digests : ABSENT_LISTS) {
                for (String reference : ABSENT_STRINGS) {
                    for (List<String> references : ABSENT_LISTS) {
                        TrustRelease release = release(digest, digests, reference, references);
                        try {
                            AttestationPolicy policy = AttestationPolicy.fromTrustRelease(release);
                            assertThat(policy.pinsImageIdentity())
                                    .as("built an unpinned policy from %s/%s/%s/%s",
                                            digest, digests, reference, references)
                                    .isTrue();
                        } catch (IllegalArgumentException expected) {
                            assertThat(expected).hasMessageContaining("pins no image identity");
                        }
                    }
                }
            }
        }
    }

    @Test
    void anEmptyReleaseIsRefused() {
        assertThatThrownBy(() -> AttestationPolicy.fromTrustRelease(emptyRelease()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pins no image identity");
    }

    @Test
    void aReleaseWhoseListsHoldNoUsableStringsFailsClosedRatherThanOpen() {
        // A list of null/empty entries is NOT empty, so the builder accepts it
        // and requireOneOf runs. It skips every unusable entry and falls through
        // to the mismatch throw, so such a policy rejects everything rather than
        // accepting everything. Useless, but safe — which is the distinction
        // that matters here, and worth pinning so a future "tidy-up" that
        // filters these lists down to empty does not silently convert this
        // fail-closed case into the fail-open one.
        TrustRelease release = release(
                null, Arrays.asList(null, ""), null, Arrays.asList(null, ""));
        AttestationPolicy policy = AttestationPolicy.fromTrustRelease(release);

        assertThat(policy.pinsImageIdentity())
                .as("a non-empty accepted list keeps requireOneOf enabled")
                .isTrue();
        assertThat(policy.getExpectedImageDigests()).isNotEmpty();
    }

    @Test
    void aReleaseWithOnlyOneIdentityKindIsAccepted() {
        // Non-vacuity requires one of the two, not both.
        TrustRelease release = release("sha256:beef", null, null, null);
        AttestationPolicy policy = AttestationPolicy.fromTrustRelease(release);

        assertThat(policy.pinsImageIdentity()).isTrue();
        assertThat(policy.getExpectedImageDigests()).containsExactly("sha256:beef");
        assertThat(policy.getExpectedImageReferences()).isEmpty();
    }

    @Test
    void aDefaultPolicyPinsNothing() {
        // The state verification must refuse. Pinned explicitly so a future
        // change cannot quietly make an unpinned policy look acceptable.
        assertThat(AttestationPolicy.builder().build().pinsImageIdentity()).isFalse();
    }

    private static String certSha() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 64; i++) { out.append('a'); }
        return out.toString();
    }

    @Test
    void aCertOnlyPolicyPinsNothing() {
        AttestationPolicy policy = AttestationPolicy.builder()
                .expectedCertSha256(certSha())
                .build();

        assertThat(policy.pinsImageIdentity())
                .as("pinning the TLS cert alone says nothing about which build answered")
                .isFalse();
    }

    // ------------------------- the guard agrees with what it guards --------

    @Test
    void pinsImageIdentityAgreesWithTheChecksItGuards() {
        List<String> values = Arrays.asList(null, "x");
        List<List<String>> lists = Arrays.asList(null, Collections.singletonList("y"));

        for (String digest : values) {
            for (List<String> digests : lists) {
                for (String reference : values) {
                    for (List<String> references : lists) {
                        AttestationPolicy policy = AttestationPolicy.builder()
                                .expectedImageDigest(digest)
                                .expectedImageDigests(digests)
                                .expectedImageReference(reference)
                                .expectedImageReferences(references)
                                .build();

                        // Mirrors the two conditions requireOneOf short-circuits
                        // on. If guard and checks drift apart, the hole reopens.
                        boolean digestCheckRuns = !policy.getExpectedImageDigests().isEmpty();
                        boolean referenceCheckRuns =
                                !policy.getExpectedImageReferences().isEmpty();

                        assertThat(policy.pinsImageIdentity())
                                .as("guard disagrees with the checks it guards")
                                .isEqualTo(digestCheckRuns || referenceCheckRuns);
                    }
                }
            }
        }
    }
}
