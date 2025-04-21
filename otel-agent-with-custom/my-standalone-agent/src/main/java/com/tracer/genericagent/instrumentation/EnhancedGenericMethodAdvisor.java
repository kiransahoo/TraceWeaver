package com.tracer.genericagent.instrumentation;

import com.tracer.genericagent.util.ConfigReader;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Enhanced advisor that ensures proper exclusion of problematic libraries
 * while maintaining compatibility with restricted environments.
 */
public class EnhancedGenericMethodAdvisor {

    // Always exclude these critical packages regardless of configuration
    private static final List<String> CRITICAL_EXCLUDES = Arrays.asList(
            "java.",
            "javax.",
            "sun.",
            "com.sun.",
            "jdk.",
            "org.jboss.logmanager",
            "org.slf4j",
            "org.apache.log4j",
            "ch.qos.logback",
            "org.apache.logging",
            "org.apache.cxf",
            "org.jaxb",
            "javax.xml.bind",
            "com.sun.xml.bind",
            "jakarta.xml.bind",
            "jakarta.",
            "com.fasterxml.jackson",
            "io.opentelemetry",
            "net.bytebuddy",
            // All logging frameworks
            "org.jboss.logmanager",
            "org.jboss.logging",
            "org.slf4j",
            "org.apache.log4j",
            "org.apache.logging",
            "org.apache.logging.log4j",
            "ch.qos.logback",
            "java.util.logging",
            "org.apache.commons.logging",
            "org.apache.juli",
            "org.apache.tomcat.juli",
            "org.apache.catalina.logger",
            "org.wildfly.common.logger",
            // All Spring Framework packages
            "org.springframework",
            "org.springframework.beans",
            "org.springframework.context",
            "org.springframework.web",
            "org.springframework.core",
            "org.springframework.aop",
            "org.springframework.boot",
            "org.springframework.util",

            // Application-specific logging packages
            "com.geico.claims.common.integration.utility.logging"
            // Other exclusions
    );

    public static void install(
            Instrumentation inst,
            List<String> packagePrefixes,      // packages to instrument
            List<String> includeMethodPatterns,
            List<String> excludeMethodPatterns
    ) {
        // Verify the SimplifiedGenericMethodAdvice class can be loaded
        try {
            Class<?> adviceClass = SimplifiedGenericMethodAdvice.class;
            System.out.println("[EnhancedAdvisor] Successfully verified advice class: " + adviceClass.getName());
        } catch (Throwable t) {
            System.err.println("[EnhancedAdvisor] ERROR: Cannot load advice class: " + t.getMessage());
            t.printStackTrace();
        }

        // Get configured excludes
        List<String> configExcludes = ConfigReader.getPackageExcludes();

        // Combine with critical excludes
        List<String> allExcludes = new ArrayList<>(CRITICAL_EXCLUDES);
        allExcludes.addAll(configExcludes);

        System.out.println("[EnhancedAdvisor] Installing with packages: " + packagePrefixes);
        System.out.println("[EnhancedAdvisor] Excluded packages: " + allExcludes);
        System.out.println("[EnhancedAdvisor] Include methods: " + includeMethodPatterns);
        System.out.println("[EnhancedAdvisor] Exclude methods: " + excludeMethodPatterns);

        // Use the most basic AgentBuilder configuration for compatibility
        AgentBuilder agentBuilder = new AgentBuilder.Default()
                .with(AgentBuilder.Listener.StreamWriting.toSystemOut().withErrorsOnly());

        // For each *included* package prefix, transform matching classes
        for (String prefix : packagePrefixes) {
            // Build initial type matcher for this package
            ElementMatcher.Junction<TypeDescription> typeMatcher = nameStartsWith(prefix);

            // Exclude all packages that should not be instrumented
            for (String exPkg : allExcludes) {
                typeMatcher = typeMatcher.and(not(nameStartsWith(exPkg)));
            }

            // Extra safety check - don't instrument known problematic packages
            typeMatcher = typeMatcher
                    .and(not(nameContains("jaxb")))
                    .and(not(nameContains("jaxws")))
                    .and(not(nameContains(".bytebuddy.")))
                    .and(not(nameContains(".opentelemetry.")))
                    // Spring Framework specific exclusions
                    .and(not(nameContains("BeanFactory")))
                    .and(not(nameContains("BeanCreation")))
                    .and(not(nameContains("ApplicationContext")))
                    .and(not(nameContains("ContextLoader")))
                    // Log4j utility specific exclusions
                    .and(not(nameContains("LoaderUtil")))
                    .and(not(nameContains("log4j.util")))
                    .and(not(nameContains("LogManager")))
                    . and(not(nameContains("Logger")))
                    .and(not(nameContains("Logging")))
                    .and(not(nameContains("Interpolator")))
                    .and(not(nameContains("ContextData")))
                    .and(not(nameContains("ClassLoaderContext")))
                    .and(not(nameContains("ReflectionUtil")));

            // Exclude all proxy classes by name pattern - prevents JBoss EJB proxy issues
            typeMatcher = typeMatcher
                    .and(not(nameContains("$$$view")))       // JBoss EJB view proxies
                    .and(not(nameContains("$Proxy")))        // JDK dynamic proxies
                    .and(not(nameContains("$$EnhancedBy")))  // Enhancement frameworks
                    .and(not(nameContains("FastClass")))     // CGLIB proxies
                    .and(not(nameContains("$$_javassist_"))) // Javassist proxies
                    .and(not(nameContains("$HibernateProxy"))) // Hibernate proxies
                    .and(not(nameContains("_WeldClientProxy"))) // CDI proxies
                    .and(not(nameContains("$EJBClient")))    // EJB client proxies
                    .and(not(nameMatches(".*\\$\\d+$")));    // Anonymous inner classes


            agentBuilder = agentBuilder
                    .type(typeMatcher)
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                        // Skip problematic classloaders
                        if (classLoader == null) {
                            System.out.println("[EnhancedAdvisor] Skipping null classloader for: " + typeDescription.getName());
                            return builder;
                        }

                        // Don't instrument our own agent classes
                        String className = typeDescription.getName();
                        if (className.contains("genericagent.instrumentation")) {
                            return builder;
                        }

                        // Log the class we're instrumenting
                        System.out.println("[EnhancedAdvisor] Instrumenting: " + className);

                        // Build method matchers
                        ElementMatcher.Junction<MethodDescription> includeMatcher;
                        if (includeMethodPatterns == null || includeMethodPatterns.isEmpty()) {
                            includeMatcher = any();
                        } else {
                            includeMatcher = none();
                            for (String inc : includeMethodPatterns) {
                                includeMatcher = includeMatcher.or(named(inc));
                            }
                        }

                        ElementMatcher.Junction<MethodDescription> excludeMatcher = none();
                        if (excludeMethodPatterns != null && !excludeMethodPatterns.isEmpty()) {
                            for (String exc : excludeMethodPatterns) {
                                excludeMatcher = excludeMatcher.or(nameContains(exc));
                            }
                        }

                        // Final method matcher with safety filters
                        ElementMatcher.Junction<MethodDescription> finalMatcher =
                                includeMatcher.and(not(excludeMatcher))
                                        .and(isPublic())
                                        .and(not(isConstructor()))
                                        .and(not(isAbstract()))
                                        .and(not(isNative()));

                        try {
                            // Revert to the original approach without specifying ClassLoader
                            return builder
                                    .method(finalMatcher)
                                    .intercept(Advice.to(SimplifiedGenericMethodAdvice.class));
                        } catch (Throwable t) {
                            System.err.println("[EnhancedAdvisor] ERROR applying instrumentation to " + className + ": " + t.getMessage());
                            t.printStackTrace();
                            return builder;
                        }
                    });
        }

        // Install the instrumentation
        try {
            agentBuilder.installOn(inst);
            System.out.println("[EnhancedAdvisor] Successfully installed instrumentation");
        } catch (Exception e) {
            System.err.println("[EnhancedAdvisor] Failed to install instrumentation: " + e.getMessage());
            e.printStackTrace();
        }
    }
}