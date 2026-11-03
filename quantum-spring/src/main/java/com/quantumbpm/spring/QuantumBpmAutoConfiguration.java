package com.quantumbpm.spring;

import com.quantumbpm.client.QuantumBPM;
import com.quantumbpm.client.auth.ClientCredentialsTokenProvider;
import com.quantumbpm.client.auth.StaticTokenProvider;
import com.quantumbpm.client.auth.TokenProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Spring Boot autoconfiguration for the QuantumBPM SDK.
 *
 * <p>Activates when {@code quantumbpm.base-url} and {@code quantumbpm.project-id}
 * are set. Wires:</p>
 *
 * <ul>
 *   <li>A {@link TokenProvider} - {@link ClientCredentialsTokenProvider} when
 *       {@code quantumbpm.auth.client-credentials.token-url} and {@code .client-id} are set, or
 *       {@link StaticTokenProvider} when {@code quantumbpm.token} is set.
 *       With neither, requests go out unauthenticated, for the devserver.</li>
 *   <li>A {@link QuantumBPM} bean built from properties.</li>
 *   <li>A {@link JobWorkerRegistrar} that scans {@link JobWorker} beans and
 *       starts a managed worker, when {@code quantumbpm.worker.enabled} is
 *       true (the default).</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(QuantumBpmProperties.class)
@ConditionalOnProperty(prefix = "quantumbpm", name = {"base-url", "project-id"})
public class QuantumBpmAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @Conditional(AuthConfigured.class)
    public TokenProvider quantumBpmTokenProvider(QuantumBpmProperties properties) {
        QuantumBpmProperties.Auth.ClientCredentials cc = properties.getAuth().getClientCredentials();
        if (clientCredentialsConfigured(cc)) {
            return new ClientCredentialsTokenProvider(
                    cc.getTokenUrl(), cc.getClientId(), cc.getClientSecret(), cc.getScopes(), cc.getAudience());
        }
        return new StaticTokenProvider(properties.getToken());
    }

    // With no token provider the client sends no Authorization header, which
    // is what the devserver expects, the same as the plain client.
    @Bean
    @ConditionalOnMissingBean
    public QuantumBPM quantumBpm(QuantumBpmProperties properties, ObjectProvider<TokenProvider> tokenProvider) {
        return QuantumBPM.builder()
                .baseUrl(properties.getBaseUrl())
                .projectId(properties.getProjectId())
                .tokenProvider(tokenProvider.getIfAvailable())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "quantumbpm.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
    public JobWorkerRegistrar quantumBpmJobWorkerRegistrar(
            QuantumBPM client,
            ConfigurableListableBeanFactory beanFactory,
            QuantumBpmProperties properties) {
        return new JobWorkerRegistrar(client, beanFactory, properties.getWorker());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean clientCredentialsConfigured(QuantumBpmProperties.Auth.ClientCredentials cc) {
        return hasText(cc.getTokenUrl()) && hasText(cc.getClientId());
    }

    /** Matches when a token or a client credentials token URL and client ID are configured. */
    static class AuthConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            QuantumBpmProperties properties = Binder.get(context.getEnvironment())
                    .bind("quantumbpm", QuantumBpmProperties.class)
                    .orElseGet(QuantumBpmProperties::new);
            return hasText(properties.getToken())
                    || clientCredentialsConfigured(properties.getAuth().getClientCredentials());
        }
    }
}
