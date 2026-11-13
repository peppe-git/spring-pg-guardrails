// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls.autoconfigure;

import io.github.peppe_git.pgguardrails.rls.TenantAdvisoryLock;
import io.github.peppe_git.pgguardrails.rls.TenantGuc;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Spring Boot auto-configuration for {@code pg-rls-core}.
 *
 * <p>The ordering names the {@code DataSource} auto-configuration of both Spring Boot 3
 * ({@code org.springframework.boot.autoconfigure.jdbc}) and Spring Boot 4 ({@code org.springframework.boot.jdbc}),
 * by name, so that one jar works with both.
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
})
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "pg-rls", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PgRlsProperties.class)
public class PgRlsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnSingleCandidate(DataSource.class)
    public TenantGuc tenantGuc(DataSource dataSource, PgRlsProperties properties) {
        return new TenantGuc(dataSource, properties.toSettings());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnSingleCandidate(DataSource.class)
    public TenantAdvisoryLock tenantAdvisoryLock(DataSource dataSource) {
        return new TenantAdvisoryLock(dataSource);
    }

    @Bean
    static RlsTransactionManagerPostProcessor rlsTransactionManagerPostProcessor(
            ObjectProvider<TenantGuc> tenantGuc, ObjectProvider<DataSource> dataSource) {
        return new RlsTransactionManagerPostProcessor(tenantGuc, dataSource);
    }

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    RuntimeRoleStartupCheck rlsRuntimeRoleStartupCheck(DataSource dataSource, PgRlsProperties properties) {
        return new RuntimeRoleStartupCheck(dataSource, properties.getRuntimeRoleCheck());
    }
}
