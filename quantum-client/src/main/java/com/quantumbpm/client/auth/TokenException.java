package com.quantumbpm.client.auth;

/** Thrown when a TokenProvider cannot produce a valid token. */
public class TokenException extends RuntimeException {
    public TokenException(String message) {
        super(message);
    }

    public TokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
