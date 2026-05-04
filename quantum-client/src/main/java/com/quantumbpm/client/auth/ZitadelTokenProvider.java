package com.quantumbpm.client.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Authenticates against Zitadel using a service-account JSON Key file via
 * the JWT Profile ({@code urn:ietf:params:oauth:grant-type:jwt-bearer})
 * grant. Tokens are cached in-memory until shortly before expiry.
 */
public final class ZitadelTokenProvider implements TokenProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String userId;
    private final String keyId;
    private final PrivateKey privateKey;
    private final String issuer;
    private final String scope;
    private final HttpClient httpClient;

    private final ReentrantLock lock = new ReentrantLock();
    private String cachedToken;
    private Instant cachedExpiry = Instant.EPOCH;

    /**
     * @param keyFilePath path to the Zitadel service-account JSON Key file
     * @param issuer      Zitadel base URL (e.g. https://auth.quantumbpm.com)
     * @param projectId   Zitadel project ID; when set, adds the audience scope
     * @throws IOException if the key file cannot be read or parsed
     */
    public ZitadelTokenProvider(String keyFilePath, String issuer, String projectId) throws IOException {
        JsonNode key = MAPPER.readTree(Files.readString(Path.of(keyFilePath)));
        this.userId = key.get("userId").asText();
        this.keyId = key.get("keyId").asText();
        this.privateKey = parseRsaPrivateKey(key.get("key").asText());
        this.issuer = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        StringBuilder s = new StringBuilder();
        s.append("openid profile ");
        s.append("urn:zitadel:iam:user:resourceowner ");
        s.append("urn:zitadel:iam:org:projects:roles");
        if (projectId != null && !projectId.isBlank()) {
            s.append(" urn:zitadel:iam:org:project:id:").append(projectId).append(":aud");
        }
        this.scope = s.toString();
    }

    @Override
    public String getToken() {
        lock.lock();
        try {
            if (cachedToken != null && Instant.now().isBefore(cachedExpiry.minusSeconds(60))) {
                return cachedToken;
            }
            return exchange();
        } finally {
            lock.unlock();
        }
    }

    private String exchange() {
        Instant now = Instant.now();
        String assertion = Jwts.builder()
                .setIssuer(userId)
                .setSubject(userId)
                .setAudience(issuer)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(3600)))
                .setHeaderParam("kid", keyId)
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();

        Map<String, String> form = new HashMap<>();
        form.put("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.put("scope", scope);
        form.put("assertion", assertion);

        StringBuilder body = new StringBuilder();
        for (var entry : form.entrySet()) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            body.append('=');
            body.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(issuer + "/oauth/v2/token"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new TokenException("zitadel token exchange failed (" + response.statusCode() + "): " + response.body());
            }
            JsonNode body2 = MAPPER.readTree(response.body());
            cachedToken = body2.get("access_token").asText();
            cachedExpiry = Instant.now().plusSeconds(body2.get("expires_in").asLong());
            return cachedToken;
        } catch (IOException e) {
            throw new TokenException("zitadel token exchange I/O error", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenException("zitadel token exchange interrupted", e);
        }
    }

    private static PrivateKey parseRsaPrivateKey(String pem) throws IOException {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object parsed = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (parsed instanceof PEMKeyPair pair) {
                return converter.getKeyPair(pair).getPrivate();
            }
            if (parsed instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo info) {
                return converter.getPrivateKey(info);
            }
            throw new IOException("unsupported PEM key type: " + (parsed == null ? "null" : parsed.getClass().getName()));
        }
    }
}
