package com.quantumbpm.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Deploys the matching BPMN and DMN files to the project on startup, DMN
 * first. Put it on any Spring bean class, typically the application class.
 * Files whose latest deployed version has the same XML are skipped.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Deployment {

    /** Spring resource patterns such as {@code classpath*:models/*.dmn}. Each file ends in .bpmn or .dmn. */
    String[] resources();
}
