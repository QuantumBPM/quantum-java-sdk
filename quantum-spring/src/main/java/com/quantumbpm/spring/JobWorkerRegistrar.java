package com.quantumbpm.spring;

import com.quantumbpm.client.QuantumBPM;
import com.quantumbpm.client.variables.Vars;
import com.quantumbpm.client.workers.Handler;
import com.quantumbpm.client.workers.Job;
import com.quantumbpm.client.workers.Worker;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.MethodIntrospector;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Discovers {@link JobWorker}-annotated methods on Spring beans and registers
 * them with a managed {@link Worker}. Implements {@link SmartLifecycle} so
 * the worker starts after the application context refreshes and stops
 * cleanly on shutdown.
 */
public class JobWorkerRegistrar implements SmartLifecycle {

    private static final Logger LOG = Logger.getLogger(JobWorkerRegistrar.class.getName());

    private final QuantumBPM client;
    private final ConfigurableListableBeanFactory beanFactory;
    private final QuantumBpmProperties.Worker config;

    private Worker worker;
    private boolean running;

    public JobWorkerRegistrar(QuantumBPM client, ConfigurableListableBeanFactory beanFactory, QuantumBpmProperties.Worker config) {
        this.client = client;
        this.beanFactory = beanFactory;
        this.config = config;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        worker = client.newWorker(config.getClientId());
        int registered = registerHandlers(worker);
        if (registered == 0) {
            LOG.info("@JobWorker scan found no handlers; skipping Worker.start()");
            worker = null;
            running = false;
            return;
        }
        worker.start();
        running = true;
        LOG.info("QuantumBPM worker started with " + registered + " handler(s)");
    }

    @Override
    public synchronized void stop() {
        if (!running) return;
        try {
            worker.stop(15_000);
        } finally {
            running = false;
            worker = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        // Start late, stop early — long-running background work.
        return Integer.MAX_VALUE - 1024;
    }

    private int registerHandlers(Worker worker) {
        int count = 0;
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> beanType;
            try {
                beanType = beanFactory.getType(beanName);
            } catch (Exception e) {
                continue;
            }
            if (beanType == null) continue;

            Map<Method, JobWorker> handlers = MethodIntrospector.selectMethods(
                    beanType,
                    (MethodIntrospector.MetadataLookup<JobWorker>) method ->
                            method.getAnnotation(JobWorker.class));
            if (handlers.isEmpty()) continue;

            Object bean = beanFactory.getBean(beanName);
            for (Map.Entry<Method, JobWorker> entry : handlers.entrySet()) {
                registerOne(worker, bean, entry.getKey(), entry.getValue());
                count++;
            }
        }
        return count;
    }

    private void registerOne(Worker worker, Object bean, Method method, JobWorker annotation) {
        if (method.getParameterCount() != 1 || !Job.class.isAssignableFrom(method.getParameterTypes()[0])) {
            throw new IllegalStateException(
                "@JobWorker method " + method + " must take a single Job<T> parameter");
        }
        Class<?> typedClass = extractJobTypeArgument(method);

        method.setAccessible(true);
        Handler<Object> handler = job -> {
            try {
                Object result = method.invoke(bean, job);
                if (result == null) return null;
                if (result instanceof Vars vars) return vars;
                throw new IllegalStateException(
                    "@JobWorker method " + method + " must return Vars or void; got " + result.getClass());
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause() == null ? ite : ite.getCause();
                if (cause instanceof Exception e) throw e;
                throw new RuntimeException(cause);
            }
        };

        worker.handle(
                annotation.type(),
                (Class<Object>) typedClass,
                handler,
                Worker.withMaxJobs(annotation.maxJobs()),
                Worker.withPollTimeout(annotation.pollTimeout()),
                Worker.withLockDuration(annotation.lockDuration()));
    }

    private static Class<?> extractJobTypeArgument(Method method) {
        Type paramType = method.getGenericParameterTypes()[0];
        if (paramType instanceof ParameterizedType parameterized) {
            Type[] args = parameterized.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> klass) {
                return klass;
            }
            if (args.length == 1 && args[0] instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
                return raw;
            }
        }
        return Map.class;
    }
}
