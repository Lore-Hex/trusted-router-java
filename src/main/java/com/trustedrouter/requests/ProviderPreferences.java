package com.trustedrouter.requests;

import com.google.gson.JsonObject;
import com.trustedrouter.TrustedRouter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Typed provider routing, privacy, billing, and performance preferences. */
public final class ProviderPreferences {
    /**
     * Allows any provider privacy level.
     */
    public static final String PRIVACY_ANY = "any";
    /**
     * Requires a provider that does not store request data.
     */
    public static final String PRIVACY_NO_STORE = "no_store";
    /**
     * Requires zero data retention.
     */
    public static final String PRIVACY_ZDR = "zdr";
    /**
     * Requires confidential compute with end-to-end encryption.
     */
    public static final String PRIVACY_CONFIDENTIAL = "confidential";

    private final JsonObject value;

    private ProviderPreferences(Builder builder) {
        value = builder.value.deepCopy();
    }

    /**
     * Creates a builder for this value.
     *
     * @return a new builder
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Requires a contractually zero-data-retention provider route.
     *
     * @return preferences requiring zero data retention
     */
    public static ProviderPreferences zeroDataRetention() {
        return builder().minimumPrivacy(PRIVACY_ZDR).build();
    }

    /**
     * Requires provider-side confidential compute and end-to-end encryption.
     *
     * @return preferences requiring confidential compute and end-to-end encryption
     */
    public static ProviderPreferences confidential() {
        return builder().minimumPrivacy(PRIVACY_CONFIDENTIAL).build();
    }

    /**
     * Requires a provider headquartered in the United States.
     *
     * @return preferences restricting provider headquarters to the United States
     */
    public static ProviderPreferences unitedStates() {
        return builder().jurisdiction("us").build();
    }

    /**
     * Returns the wire JSON representation.
     *
     * @return the wire JSON representation
     */
    public JsonObject toJson() { return value.deepCopy(); }

    /**
     * Represents builder.
     */
    public static final class Builder {
        private final JsonObject value = new JsonObject();
        private Builder() {}

        /**
         * Sets order.
         *
         * @param providers the providers
         * @return this builder
         */
        public Builder order(String... providers) {
            return order(Arrays.asList(providers));
        }
        /**
         * Sets order.
         *
         * @param providers the providers
         * @return this builder
         */
        public Builder order(List<String> providers) {
            value.add("order", TrustedRouter.stringArray(providers));
            return this;
        }
        /**
         * Sets only.
         *
         * @param providers the providers
         * @return this builder
         */
        public Builder only(String... providers) {
            return only(Arrays.asList(providers));
        }
        /**
         * Sets only.
         *
         * @param providers the providers
         * @return this builder
         */
        public Builder only(List<String> providers) {
            value.add("only", TrustedRouter.stringArray(providers));
            return this;
        }
        /**
         * Sets ignore.
         *
         * @param providers the providers
         * @return this builder
         */
        public Builder ignore(String... providers) {
            return ignore(Arrays.asList(providers));
        }
        /**
         * Sets ignore.
         *
         * @param providers the providers
         * @return this builder
         */
        public Builder ignore(List<String> providers) {
            value.add("ignore", TrustedRouter.stringArray(providers));
            return this;
        }
        /**
         * Sets allow fallbacks.
         *
         * @param allow the allow
         * @return this builder
         */
        public Builder allowFallbacks(boolean allow) {
            value.addProperty("allow_fallbacks", allow);
            return this;
        }
        /**
         * Sets require parameters.
         *
         * @param require the require
         * @return this builder
         */
        public Builder requireParameters(boolean require) {
            value.addProperty("require_parameters", require);
            return this;
        }
        /**
         * Sets data collection.
         *
         * @param policy the policy
         * @return this builder
         */
        public Builder dataCollection(String policy) {
            requireOneOf("dataCollection", policy, "allow", "deny");
            value.addProperty("data_collection", policy.toLowerCase(Locale.ROOT));
            return this;
        }
        /**
         * Sets minimum privacy.
         *
         * @param privacy the privacy
         * @return this builder
         */
        public Builder minimumPrivacy(String privacy) {
            requireOneOf("minimumPrivacy", privacy,
                    "any", "no_store", "zdr", "confidential", "e2e", "e2ee");
            value.addProperty("min_privacy", privacy.toLowerCase(Locale.ROOT));
            return this;
        }
        /**
         * Sets sort.
         *
         * @param mode the mode
         * @return this builder
         */
        public Builder sort(String mode) {
            requireOneOf("sort", mode, "price", "latency", "throughput");
            value.addProperty("sort", mode.toLowerCase(Locale.ROOT));
            return this;
        }
        /**
         * Sets usage.
         *
         * @param usage the usage
         * @return this builder
         */
        public Builder usage(String usage) {
            requireOneOf("usage", usage, "credits", "byok");
            value.addProperty("usage", usage.toLowerCase(Locale.ROOT));
            return this;
        }
        /**
         * Sets jurisdiction.
         *
         * @param jurisdiction the jurisdiction
         * @return this builder
         */
        public Builder jurisdiction(String jurisdiction) {
            requireOneOf("jurisdiction", jurisdiction, "us");
            value.addProperty("jurisdiction", "us");
            return this;
        }
        /**
         * Sets quantizations.
         *
         * @param quantizations the quantizations
         * @return this builder
         */
        public Builder quantizations(List<String> quantizations) {
            value.add("quantizations", TrustedRouter.stringArray(quantizations));
            return this;
        }
        /**
         * Sets max price.
         *
         * @param maxPrice the max price
         * @return this builder
         */
        public Builder maxPrice(JsonObject maxPrice) {
            if (maxPrice == null) {
                throw new IllegalArgumentException("maxPrice must not be null");
            }
            value.add("max_price", maxPrice.deepCopy());
            return this;
        }
        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
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
