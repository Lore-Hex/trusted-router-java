package com.trustedrouter.attestation;

import java.security.GeneralSecurityException;

/** Fail-closed attestation signature or claim verification failure. */
public final class AttestationVerificationException extends GeneralSecurityException {
    private static final long serialVersionUID = 1L;
    public AttestationVerificationException(String message) { super(message); }
    public AttestationVerificationException(String message, Throwable cause) { super(message, cause); }
}
