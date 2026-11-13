// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.pgguardrails.guardrails.rules.AsyncExecutorDeclared;
import io.github.peppe_git.pgguardrails.guardrails.support.Finding;
import io.github.peppe_git.pgguardrails.guardrails.support.GuardReport;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Asks a real Spring context which executor {@code @Async} would get. */
class AsyncExecutorDeclaredTest {

    static class Mailer {
        @Async
        public void send() {
        }
    }

    static class Reporter {
        @Async("reportExecutor")
        public void build() {
        }
    }

    @Configuration
    static class AsyncNotEnabled {
        @Bean
        Mailer mailer() {
            return new Mailer();
        }
    }

    @Configuration
    @EnableAsync
    static class TwoExecutorsNoDefault {
        @Bean
        Mailer mailer() {
            return new Mailer();
        }

        @Bean
        ThreadPoolTaskExecutor jobExecutor() {
            return new ThreadPoolTaskExecutor();
        }

        @Bean
        ThreadPoolTaskExecutor indexExecutor() {
            return new ThreadPoolTaskExecutor();
        }
    }

    @Configuration
    @EnableAsync
    static class NamedDefault extends TwoExecutorsNoDefault {
        @Bean
        ThreadPoolTaskExecutor taskExecutor() {
            return new ThreadPoolTaskExecutor();
        }
    }

    @Configuration
    @EnableAsync
    static class ConfigurerChooses extends TwoExecutorsNoDefault implements AsyncConfigurer {
        @Override
        public Executor getAsyncExecutor() {
            return Runnable::run;
        }
    }

    @Configuration
    @EnableAsync
    static class MissingQualifiedExecutor {
        @Bean
        Reporter reporter() {
            return new Reporter();
        }

        @Bean
        ThreadPoolTaskExecutor taskExecutor() {
            return new ThreadPoolTaskExecutor();
        }
    }

    private static GuardReport check(Class<?> config) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(config)) {
            return AsyncExecutorDeclared.in(context).check();
        }
    }

    @Test
    void asyncWithoutEnableAsyncIsInert() {
        assertThat(check(AsyncNotEnabled.class).violations()).extracting(Finding::id)
                .containsExactly("async-not-enabled");
    }

    @Test
    void severalExecutorsAndNoneNamedTaskExecutorFallBackToTheUnboundedOne() {
        GuardReport report = check(TwoExecutorsNoDefault.class);
        assertThat(report.violations()).extracting(Finding::id).containsExactly("default-executor");
        assertThat(report.violations().get(0).message()).contains("SimpleAsyncTaskExecutor");
    }

    @Test
    void aBeanNamedTaskExecutorIsAChoice() {
        GuardReport report = check(NamedDefault.class);
        assertThat(report.isSatisfied()).as(report.describe()).isTrue();
    }

    @Test
    void anAsyncConfigurerThatOverridesTheExecutorIsAChoice() {
        GuardReport report = check(ConfigurerChooses.class);
        assertThat(report.isSatisfied()).as(report.describe()).isTrue();
    }

    @Test
    void aQualifierMustNameAnExistingExecutor() {
        assertThat(check(MissingQualifiedExecutor.class).violations()).extracting(Finding::id)
                .containsExactly("qualifier:reportExecutor");
    }
}
