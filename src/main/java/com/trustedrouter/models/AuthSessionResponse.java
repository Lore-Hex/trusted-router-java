package com.trustedrouter.models;

/** Current browser/session authentication state. */
public final class AuthSessionResponse extends JsonModel {
    private boolean authenticated;
    private User user;
    /**
     * Returns authenticated.
     *
     * @return the authenticated
     */
    public boolean isAuthenticated() { return authenticated; }
    /**
     * Returns user.
     *
     * @return the user
     */
    public User getUser() { return user; }
    /**
     * Represents user.
     */
    public static final class User {
        private String id;
        private String email;
        /**
         * Returns id.
         *
         * @return the id
         */
        public String getId() { return id; }
        /**
         * Returns email.
         *
         * @return the email
         */
        public String getEmail() { return email; }
    }
}
