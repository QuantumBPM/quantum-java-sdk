package com.quantumbpm.spring;

import com.quantumbpm.client.QuantumBPM;
import com.quantumbpm.client.auth.StaticTokenProvider;
import com.quantumbpm.client.auth.TokenProvider;
import com.quantumbpm.client.auth.ZitadelTokenProvider;
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

import java.io.IOException;

/**
 * Spring Boot autoconfiguration for the QuantumBPM SDK.
 *
 * <p>Activates when {@code quantumbpm.base-url} and {@code quantumbpm.project-id}
 * are set. Wires:</p>
 *
 * <ul>
 *   <li>A {@link TokenProvider} - {@link ZitadelTokenProvider} when
 *       {@code quantumbpm.auth.zitadel.key-file} is set, or
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
    public TokenProvider quantumBpmTokenProvider(QuantumBpmProperties properties) throws IOException {
        QuantumBpmProperties.Auth.Zitadel zitadel = properties.getAuth().getZitadel();
        if (hasText(zitadel.getKeyFile())) {
            return new ZitadelTokenProvider(zitadel.getKeyFile(), zitadel.getIssuer(), zitadel.getProjectId());
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

    /** Matches when a token or a Zitadel key file is configured. */
    static class AuthConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            QuantumBpmProperties properties = Binder.get(context.getEnvironment())
                    .bind("quantumbpm", QuantumBpmProperties.class)
                    .orElseGet(QuantumBpmProperties::new);
            return hasText(properties.getToken()) || hasText(properties.getAuth().getZitadel().getKeyFile());
        }
    }
}
