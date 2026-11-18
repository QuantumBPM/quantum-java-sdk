package com.quantumbpm.spring;

import com.quantumbpm.client.QuantumBPM;
import com.quantumbpm.client.generated.ApiException;
import com.quantumbpm.client.generated.api.DefaultApi;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Deploys the files named by {@link Deployment} annotations once every
 * singleton exists, before the job worker starts. A file that cannot be
 * deployed fails startup.
 */
public class ModelDeployer implements SmartInitializingSingleton {

    private static final Logger LOG = Logger.getLogger(ModelDeployer.class.getName());
    private static final int CONFLICT = 409;

    private final QuantumBPM client;
    private final ConfigurableListableBeanFactory beanFactory;
    private final ResourcePatternResolver resolver;

    public ModelDeployer(QuantumBPM client, ConfigurableListableBeanFactory beanFactory, ResourcePatternResolver resolver) {
        this.client = client;
        this.beanFactory = beanFactory;
        this.resolver = resolver;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Set<Resource> dmn = new LinkedHashSet<>();
        Set<Resource> bpmn = new LinkedHashSet<>();
        for (String beanName : beanFactory.getBeanNamesForAnnotation(Deployment.class)) {
            Deployment annotation = beanFactory.findAnnotationOnBean(beanName, Deployment.class);
            if (annotation == null) continue;
            for (String pattern : annotation.resources()) {
                for (Resource resource : resolve(pattern)) {
                    String name = String.valueOf(resource.getFilename());
                    if (name.endsWith(".dmn")) dmn.add(resource);
                    else if (name.endsWith(".bpmn")) bpmn.add(resource);
                    else throw new IllegalStateException("@Deployment file " + name + " is neither .bpmn nor .dmn");
                }
            }
        }
        // Decisions first, so no process starts before the decisions it calls exist.
        for (Resource resource : dmn) deploy(resource, true);
        for (Resource resource : bpmn) deploy(resource, false);
    }

    private Resource[] resolve(String pattern) {
        try {
            Resource[] found = resolver.getResources(pattern);
            if (found.length == 0) throw new IllegalStateException("@Deployment pattern " + pattern + " matches no files");
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException("@Deployment pattern " + pattern + " cannot be resolved", e);
        }
    }

    private void deploy(Resource resource, boolean dmn) {
        String name = resource.getFilename();
        String xml = read(resource);
        UUID draft;
        try {
            draft = dmn ? client.dmn().createDefinition(name, xml).getId() : client.bpmn().createResource(name, xml).getId();
        } catch (ApiException e) {
            // The server refuses a create only for XML identical to the latest deployed version.
            if (e.getCode() == CONFLICT) {
                LOG.info("QuantumBPM: " + name + " is unchanged since its last deploy");
                return;
            }
            throw failure(name, "save", e);
        }
        try {
            if (dmn) client.dmn().deployDefinition(draft);
            else client.bpmn().deployResource(draft);
            LOG.info("QuantumBPM: deployed " + name);
        } catch (ApiException e) {
            deleteDraft(name, draft, dmn);
            // Another instance deployed the same XML between this create and deploy.
            if (e.getCode() == CONFLICT) {
                LOG.info("QuantumBPM: " + name + " was deployed by another instance");
                return;
            }
            throw failure(name, "deploy", e);
        }
    }

    private void deleteDraft(String name, UUID draft, boolean dmn) {
        try {
            if (dmn) new DefaultApi(client.raw()).deleteDefinition(client.projectId(), draft);
            else client.bpmn().deleteResource(draft);
        } catch (ApiException e) {
            LOG.log(Level.WARNING, "QuantumBPM: cannot delete the draft " + draft + " of " + name, e);
        }
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("@Deployment file " + resource.getDescription() + " cannot be read", e);
        }
    }

    private static IllegalStateException failure(String name, String step, ApiException e) {
        String status = e.getCode() == 0 ? e.getMessage() : "HTTP " + e.getCode() + " " + e.getResponseBody();
        return new IllegalStateException("QuantumBPM: cannot " + step + " " + name + ": " + status, e);
    }
}
