package com.trustedrouter.models;

/** OIDC-style identity bound to a delegated TrustedRouter key. */
public final class UserInfoResponse extends JsonModel {
    private UserInfo data;
    /**
     * Returns data.
     *
     * @return the data
     */
    public UserInfo getData() { return data; }
    /**
     * Represents user info.
     */
    public static final class UserInfo {
        private String sub;
        private String email;
        private Boolean emailVerified;
        private String walletAddress;
        private String workspaceId;
        private String createdAt;
        /**
         * Returns sub.
         *
         * @return the sub
         */
        public String getSub() { return sub; }
        /**
         * Returns email.
         *
         * @return the email
         */
        public String getEmail() { return email; }
        /**
         * Returns email verified.
         *
         * @return the email verified
         */
        public Boolean getEmailVerified() { return emailVerified; }
        /**
         * Returns wallet address.
         *
         * @return the wallet address
         */
        public String getWalletAddress() { return walletAddress; }
        /**
         * Returns workspace id.
         *
         * @return the workspace id
         */
        public String getWorkspaceId() { return workspaceId; }
        /**
         * Returns created at.
         *
         * @return the created at
         */
        public String getCreatedAt() { return createdAt; }
    }
}
