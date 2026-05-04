package com.quantumbpm.client.auth;

/** Long-lived bearer token. Useful for Enterprise API keys and tests. */
public final class StaticTokenProvider implements TokenProvider {

    private final String token;

    public StaticTokenProvider(String token) {
        this.token = token;
    }

    @Override
    public String getToken() {
        return token;
    }
}
