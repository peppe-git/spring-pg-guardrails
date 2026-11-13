// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.support;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * The single entry point to production bytecode, with the one check that bytecode rules tend to forget: that
 * something was imported at all.
 *
 * <p>{@code new ClassFileImporter().importPackages("com.acme.typo")} returns zero classes without complaint. Every
 * rule then loops over nothing, collects no violation, and is green. A renamed base package or an empty
 * {@code target/classes} switches a whole suite off in silence.
 */
public final class ProductionClasses {

    private ProductionClasses() {
    }

    /** Production classes (tests and jars excluded) of the given packages; fails if fewer than {@code minimum}. */
    public static JavaClasses of(int minimum, String... packages) {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages(packages);
        if (classes.size() < minimum) {
            throw new AssertionError("imported " + classes.size() + " classes from " + String.join(", ", packages)
                    + ", fewer than " + minimum + ": the package name is wrong or the classes are not compiled. Every "
                    + "rule fed with this would be green without having looked at anything.");
        }
        return classes;
    }

    /** {@link #of(int, String...)} with a floor of one class. */
    public static JavaClasses of(String... packages) {
        return of(1, packages);
    }
}
