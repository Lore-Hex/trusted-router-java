package com.trustedrouter.requests;

import com.google.gson.JsonObject;
import com.trustedrouter.TrustedRouter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Typed provider routing, privacy, billing, and performance preferences. */
public final class ProviderPreferences {
    public static final String PRIVACY_ANY = "any";
    public static final String PRIVACY_NO_STORE = "no_store";
    public static final String PRIVACY_ZDR = "zdr";
    public static final String PRIVACY_CONFIDENTIAL = "confidential";

    private final JsonObject value;

    private ProviderPreferences(Builder builder) {
        value = builder.value.deepCopy();
    }

    public static Builder builder() { return new Builder(); }

    /** Requires a contractually zero-data-retention provider route. */
    public static ProviderPreferences zeroDataRetention() {
        return builder().minimumPrivacy(PRIVACY_ZDR).build();
    }

    /** Requires provider-side confidential compute and end-to-end encryption. */
    public static ProviderPreferences confidential() {
        return builder().minimumPrivacy(PRIVACY_CONFIDENTIAL).build();
    }

    /** Requires a provider headquartered in the United States. */
    public static ProviderPreferences unitedStates() {
        return builder().jurisdiction("us").build();
    }

    public JsonObject toJson() { return value.deepCopy(); }

    public static final class Builder {
        private final JsonObject value = new JsonObject();
        private Builder() {}

        public Builder order(String... providers) {
            return order(Arrays.asList(providers));
        }
        public Builder order(List<String> providers) {
            value.add("order", TrustedRouter.stringArray(providers));
            return this;
        }
        public Builder only(String... providers) {
            return only(Arrays.asList(providers));
        }
        public Builder only(List<String> providers) {
            value.add("only", TrustedRouter.stringArray(providers));
            return this;
        }
        public Builder ignore(String... providers) {
            return ignore(Arrays.asList(providers));
        }
        public Builder ignore(List<String> providers) {
            value.add("ignore", TrustedRouter.stringArray(providers));
            return this;
        }
        public Builder allowFallbacks(boolean allow) {
            value.addProperty("allow_fallbacks", allow);
            return this;
        }
        public Builder dataCollection(String policy) {
            requireOneOf("dataCollection", policy, "allow", "deny");
            value.addProperty("data_collection", policy.toLowerCase(Locale.ROOT));
            return this;
        }
        public Builder minimumPrivacy(String privacy) {
            requireOneOf("minimumPrivacy", privacy,
                    "any", "no_store", "zdr", "confidential", "e2e", "e2ee");
            value.addProperty("min_privacy", privacy.toLowerCase(Locale.ROOT));
            return this;
        }
        public Builder sort(String mode) {
            requireOneOf("sort", mode, "price", "latency", "throughput");
            value.addProperty("sort", mode.toLowerCase(Locale.ROOT));
            return this;
        }
        public Builder usage(String usage) {
            requireOneOf("usage", usage, "credits", "byok");
            value.addProperty("usage", usage.toLowerCase(Locale.ROOT));
            return this;
        }
        public Builder jurisdiction(String jurisdiction) {
            requireOneOf("jurisdiction", jurisdiction, "us");
            value.addProperty("jurisdiction", "us");
            return this;
        }
        public ProviderPreferences build() { return new ProviderPreferences(this); }

        private static void requireOneOf(String name, String value, String... allowed) {
            if (value != null) {
                for (String candidate : allowed) {
                    if (candidate.equalsIgnoreCase(value.trim())) { return; }
                }
            }
            throw new IllegalArgumentException(name + " has an unsupported value");
        }
    }
}
