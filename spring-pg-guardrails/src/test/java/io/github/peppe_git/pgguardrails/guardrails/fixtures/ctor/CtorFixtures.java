// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.fixtures.ctor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/** Fixtures for UnambiguousComponentConstructors: two violations, two clean, one ignored. */
public final class CtorFixtures {

    private CtorFixtures() {
    }

    @Component
    public static class SingleConstructor {
        public SingleConstructor(String a) {
        }
    }

    @Service
    public static class TwoConstructorsNoneDesignated {
        public TwoConstructorsNoneDesignated(String a) {
        }

        public TwoConstructorsNoneDesignated(String a, Integer b) {
        }
    }

    @Component
    public static class TwoConstructorsOneAutowired {
        @Autowired
        public TwoConstructorsOneAutowired(String a) {
        }

        TwoConstructorsOneAutowired(String a, Integer b) {
        }
    }

    @Configuration
    public static class ConfigurationWithNoArgFallback {
        public ConfigurationWithNoArgFallback() {
        }

        public ConfigurationWithNoArgFallback(String a) {
        }
    }

    public static class NotAComponent {
        public NotAComponent() {
        }

        public NotAComponent(String a) {
        }
    }
}
