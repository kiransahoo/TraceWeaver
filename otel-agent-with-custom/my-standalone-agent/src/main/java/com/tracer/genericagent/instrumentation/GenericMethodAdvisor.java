package com.tracer.genericagent.instrumentation;

import com.tracer.genericagent.util.ConfigReader;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Installs ByteBuddy instrumentation using MethodDelegation instead of Advice.
 * This approach is more compatible with restrictive environments.
 */
public class GenericMethodAdvisor {

    public static void install(
            Instrumentation inst,
            List<String> packagePrefixes,      // packages to instrument
            List<String> includeMethodPatterns,
            List<String> excludeMethodPatterns
    ) {
        // Read configuration
        List<String> excludePackages = ConfigReader.getPackageExcludes();
        boolean strictMode = ConfigReader.isStrictMode();

        System.out.println(String.format(
                "[GenericMethodAdvisor] Installing with packages=%s, excludePackages=%s, includeMethods=%s, excludeMethods=%s, strictMode=%s",
                packagePrefixes, excludePackages, includeMethodPatterns, excludeMethodPatterns, strictMode
        ));

        // Extract root package identifiers for exact matching
        Set<String> rootIdentifiers = extractRootIdentifiers(packagePrefixes);

        // Configure ByteBuddy agent with COMPATIBLE strategies
        AgentBuilder agentBuilder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onError(
                            String typeName,
                            ClassLoader classLoader,
                            JavaModule module,
                            boolean loaded,
                            Throwable throwable) {
                        System.out.println("[GenericMethodAdvisor] ⚠️ ERROR instrumenting " + typeName + ": " + throwable.getMessage());
                    }

                    @Override
                    public void onTransformation(
                            TypeDescription typeDescription,
                            ClassLoader classLoader,
                            JavaModule module,
                            boolean loaded,
                            DynamicType dynamicType) {
                        System.out.println("[GenericMethodAdvisor] ✅ Successfully instrumented: " + typeDescription.getName());
                    }
                });

        // For each *included* package prefix, transform matching classes
        for (String prefix : packagePrefixes) {
            // Build a type matcher that starts with our prefix
            ElementMatcher.Junction<TypeDescription> typeMatcher = nameStartsWith(prefix);

            // In strict mode, ensure exact package matching
            if (strictMode) {
                typeMatcher = buildStrictMatcher(typeMatcher, prefix);
            }

            // Exclude any packages in "excludePackages"
            for (String exPkg : excludePackages) {
                typeMatcher = typeMatcher.and(not(nameStartsWith(exPkg)));
            }

            agentBuilder = agentBuilder
                    .type(typeMatcher)
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                        // Skip problematic classloaders
                        if (classLoader == null || isBootstrapClassLoader(classLoader)) {
                            return builder;
                        }

                        // Skip library/framework classes
                        String className = typeDescription.getName();
                        boolean matchesAllowedPackage = false;
                        for (String packageId : rootIdentifiers) {
                            if (className.contains(packageId)) {
                                matchesAllowedPackage = true;
                                break;
                            }
                        }

                        if (!matchesAllowedPackage &&
                                (className.startsWith("org.") ||
                                        className.startsWith("com.") ||
                                        className.startsWith("io.") ||
                                        className.startsWith("net."))) {
                            return builder;
                        }

                        System.out.println("[GenericMethodAdvisor] Preparing to instrument: " + className);

                        // Build method matcher
                        ElementMatcher.Junction<MethodDescription> methodMatcher;

                        // Include specified methods or use defaults
                        if (includeMethodPatterns == null || includeMethodPatterns.isEmpty()) {
                            methodMatcher = isPublic().and(not(isConstructor())).and(not(isTypeInitializer()));
                        } else {
                            methodMatcher = none();
                            for (String inc : includeMethodPatterns) {
                                methodMatcher = methodMatcher.or(named(inc));
                            }
                        }

                        // Exclude specified methods
                        if (excludeMethodPatterns != null && !excludeMethodPatterns.isEmpty()) {
                            for (String exc : excludeMethodPatterns) {
                                methodMatcher = methodMatcher.and(not(nameContains(exc)));
                            }
                        }

                        // Simple safety exclusions
                        methodMatcher = methodMatcher
                                .and(not(isGetter().or(isSetter())))
                                .and(not(nameStartsWith("access$")))
                                .and(not(nameContains("$")));

                        // Use MethodDelegation instead of Advice
                        return builder
                                .method(methodMatcher)
                                .intercept(MethodDelegation.to(TracingInterceptor.class));
                    });
        }

        // Install the instrumentation
        try {
            agentBuilder.installOn(inst);
            System.out.println("[GenericMethodAdvisor] Successfully installed instrumentation");
        } catch (Exception e) {
            System.err.println("[GenericMethodAdvisor] Error installing instrumentation: " + e.getMessage());
            e.printStackTrace();

            // Try fallback approach if needed
            tryFallbackInstrumentation(inst, packagePrefixes, includeMethodPatterns, excludeMethodPatterns);
        }
    }

    private static void tryFallbackInstrumentation(
            Instrumentation inst,
            List<String> packagePrefixes,
            List<String> includeMethodPatterns,
            List<String> excludeMethodPatterns) {

        System.out.println("[GenericMethodAdvisor] Attempting fallback instrumentation...");

        try {
            // Create the simplest possible agent
            AgentBuilder simpleAgent = new AgentBuilder.Default();

            for (String prefix : packagePrefixes) {
                simpleAgent = simpleAgent
                        .type(nameStartsWith(prefix))
                        .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                            if (classLoader == null) {
                                return builder;
                            }

                            // Use a very simple method matcher
                            ElementMatcher.Junction<MethodDescription> methodMatcher =
                                    isPublic().and(not(isConstructor())).and(not(isAbstract()));

                            // Add method excludes
                            if (excludeMethodPatterns != null) {
                                for (String exc : excludeMethodPatterns) {
                                    methodMatcher = methodMatcher.and(not(nameContains(exc)));
                                }
                            }

                            return builder
                                    .method(methodMatcher)
                                    .intercept(MethodDelegation.to(MinimalInterceptor.class));
                        });
            }

            simpleAgent.installOn(inst);
            System.out.println("[GenericMethodAdvisor] Fallback instrumentation successful");

        } catch (Exception e2) {
            System.err.println("[GenericMethodAdvisor] Fallback instrumentation also failed: " + e2.getMessage());
        }
    }

    private static Set<String> extractRootIdentifiers(List<String> packagePrefixes) {
        Set<String> rootIds = new HashSet<>();

        for (String pkg : packagePrefixes) {
            String[] parts = pkg.split("\\.");
            if (parts.length > 0) {
                rootIds.add(parts[parts.length - 1]);
            }
        }

        System.out.println("[GenericMethodAdvisor] Extracted root identifiers for validation: " + rootIds);
        return rootIds;
    }

    private static ElementMatcher.Junction<TypeDescription> buildStrictMatcher(
            ElementMatcher.Junction<TypeDescription> base, String prefix) {

        ElementMatcher.Junction<TypeDescription> matcher = base;

        int lastDotIndex = prefix.lastIndexOf('.');
        if (lastDotIndex >= 0 && lastDotIndex < prefix.length() - 1) {
            String basePkg = prefix.substring(0, lastDotIndex);
            String lastSegment = prefix.substring(lastDotIndex + 1);

            for (char c = 'a'; c <= 'z'; c++) {
                String falsePkg = basePkg + "." + lastSegment + c;
                matcher = matcher.and(not(nameStartsWith(falsePkg)));
            }
        }

        return matcher;
    }

    private static boolean isBootstrapClassLoader(ClassLoader loader) {
        return loader == null;
    }
}