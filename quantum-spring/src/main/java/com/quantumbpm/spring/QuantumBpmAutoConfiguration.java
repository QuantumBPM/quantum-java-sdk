package com.quantumbpm.spring;

import com.quantumbpm.client.QuantumBPM;
import com.quantumbpm.client.auth.StaticTokenProvider;
import com.quantumbpm.client.auth.TokenProvider;
import com.quantumbpm.client.auth.ZitadelTokenProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
 *       {@link StaticTokenProvider} when {@code quantumbpm.token} is set.</li>
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
    public TokenProvider quantumBpmTokenProvider(QuantumBpmProperties properties) throws IOException {
        QuantumBpmProperties.Auth.Zitadel zitadel = properties.getAuth().getZitadel();
        if (zitadel.getKeyFile() != null && !zitadel.getKeyFile().isBlank()) {
            return new ZitadelTokenProvider(zitadel.getKeyFile(), zitadel.getIssuer(), zitadel.getProjectId());
        }
        if (properties.getToken() != null && !properties.getToken().isBlank()) {
            return new StaticTokenProvider(properties.getToken());
        }
        throw new IllegalStateException(
            "QuantumBPM autoconfig requires either 'quantumbpm.token' or 'quantumbpm.auth.zitadel.key-file', " +
            "or a TokenProvider bean.");
    }

    @Bean
    @ConditionalOnMissingBean
    public QuantumBPM quantumBpm(QuantumBpmProperties properties, TokenProvider tokenProvider) {
        return QuantumBPM.builder()
                .baseUrl(properties.getBaseUrl())
                .projectId(properties.getProjectId())
                .tokenProvider(tokenProvider)
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
}
