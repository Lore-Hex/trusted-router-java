package com.trustedrouter.models;

/** OIDC-style identity bound to a delegated TrustedRouter key. */
public final class UserInfoResponse extends JsonModel {
    private UserInfo data;
    public UserInfo getData() { return data; }
    public static final class UserInfo {
        private String sub;
        private String email;
        private Boolean emailVerified;
        private String walletAddress;
        private String workspaceId;
        private String createdAt;
        public String getSub() { return sub; }
        public String getEmail() { return email; }
        public Boolean getEmailVerified() { return emailVerified; }
        public String getWalletAddress() { return walletAddress; }
        public String getWorkspaceId() { return workspaceId; }
        public String getCreatedAt() { return createdAt; }
    }
}
