package com.myorg.genericagent.instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.lang.instrument.Instrumentation;
import java.util.List;
//import java.util.logging.Logger;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Installs ByteBuddy instrumentation for any packages in {@code packagePrefixes},
 * including methods in {@code includeMethodPatterns} (if not empty) and
 * excluding methods in {@code excludeMethodPatterns}.
 *
 * If {@code includeMethodPatterns} is empty, we default to "any()" (i.e. all methods),
 * minus whatever is in the exclusion list.
 *
 * By default, we use 'named(...)' for includes, but 'nameContains(...)' for excludes
 * so partial matches for 'randomMath' are excluded if the method has that substring.
 *
 * @author kiransahoo
 */
public class GenericMethodAdvisor {

    public static void install(
            Instrumentation inst,
            List<String> packagePrefixes,      // packages to instrument
            List<String> includeMethodPatterns,
            List<String> excludeMethodPatterns
    ) {
        // Also read the excludePackages from ConfigReader
        List<String> excludePackages = com.myorg.genericagent.util.ConfigReader.getPackageExcludes();

        System.out.println(String.format(
                "[GenericMethodAdvisor] Installing with packages=%s, excludePackages=%s, includeMethods=%s, excludeMethods=%s",
                packagePrefixes, excludePackages, includeMethodPatterns, excludeMethodPatterns
        ));

        AgentBuilder agentBuilder = new AgentBuilder.Default();

        // For each *included* package prefix, transform matching classes
        for (String prefix : packagePrefixes) {
            // We'll build a type matcher that:
            // (nameStartsWith(prefix)) AND (NOT nameStartsWith(any excludePackage))
            ElementMatcher.Junction typeMatcher = nameStartsWith(prefix);

            // Exclude any packages in "excludePackages"
            for (String exPkg : excludePackages) {
                typeMatcher = typeMatcher.and(not(nameStartsWith(exPkg)));
            }

            agentBuilder = agentBuilder
                    .type(typeMatcher)
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {

                        // 1) Build an ElementMatcher for included methods
                        ElementMatcher.Junction<MethodDescription> includeMatcher;
                        if (includeMethodPatterns == null || includeMethodPatterns.isEmpty()) {
                            // If no includes, instrument "any()" methods
                            includeMatcher = any();
                        } else {
                            // OR all included patterns using 'named(...)'
                            includeMatcher = none();
                            for (String inc : includeMethodPatterns) {
                                includeMatcher = includeMatcher.or(named(inc));
                            }
                        }

                        // 2) Build an ElementMatcher for excluded methods using 'nameContains(...)'
                        ElementMatcher.Junction<MethodDescription> excludeMatcher = none();
                        if (excludeMethodPatterns != null && !excludeMethodPatterns.isEmpty()) {
                            for (String exc : excludeMethodPatterns) {
                                excludeMatcher = excludeMatcher.or(nameContains(exc));
                            }
                        }

                        // 3) Final method matcher = included minus excluded
                        ElementMatcher.Junction<MethodDescription> finalMatcher =
                                includeMatcher.and(not(excludeMatcher));

                        // 4) Intercept the final matcher with ByteBuddy Advice
                        return builder
                                .method(finalMatcher)
                                .intercept(Advice.to(GenericMethodAdvice.class));
                    });
        }

        // Finally, install everything on the instrumentation
        agentBuilder.installOn(inst);
    }
}