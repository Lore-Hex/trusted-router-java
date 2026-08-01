package com.trustedrouter.models;

/** Current browser/session authentication state. */
public final class AuthSessionResponse extends JsonModel {
    private boolean authenticated;
    private User user;
    public boolean isAuthenticated() { return authenticated; }
    public User getUser() { return user; }
    public static final class User {
        private String id;
        private String email;
        public String getId() { return id; }
        public String getEmail() { return email; }
    }
}
