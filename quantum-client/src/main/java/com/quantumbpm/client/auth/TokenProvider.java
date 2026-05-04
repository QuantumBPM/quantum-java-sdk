package com.quantumbpm.client.auth;

/**
 * Returns a valid bearer token for the next request.
 * Implementations are responsible for caching.
 */
@FunctionalInterface
public interface TokenProvider {
    String getToken() throws TokenException;
}
