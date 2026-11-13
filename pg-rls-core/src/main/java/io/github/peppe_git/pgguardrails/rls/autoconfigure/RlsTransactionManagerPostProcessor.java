// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls.autoconfigure;

import io.github.peppe_git.pgguardrails.rls.RlsTransactionListener;
import io.github.peppe_git.pgguardrails.rls.TenantGuc;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.util.ClassUtils;

/**
 * Registers an {@link RlsTransactionListener} on every transaction manager whose {@code DataSource} is the one the
 * {@link TenantGuc} bean writes to. Transaction managers of other data sources are left alone: register a listener
 * by hand there if they need it.
 */
final class RlsTransactionManagerPostProcessor implements BeanPostProcessor {

    private static final Log log = LogFactory.getLog(RlsTransactionManagerPostProcessor.class);

    private static final boolean JPA_PRESENT = ClassUtils.isPresent(
            "org.springframework.orm.jpa.JpaTransactionManager", RlsTransactionManagerPostProcessor.class.getClassLoader());

    private final ObjectProvider<TenantGuc> tenantGuc;
    private final ObjectProvider<DataSource> dataSource;

    RlsTransactionManagerPostProcessor(ObjectProvider<TenantGuc> tenantGuc, ObjectProvider<DataSource> dataSource) {
        this.tenantGuc = tenantGuc;
        this.dataSource = dataSource;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof ConfigurableTransactionManager manager)) {
            return bean;
        }
        DataSource managed = dataSourceOf(bean);
        DataSource rls = dataSource.getIfUnique();
        if (managed == null || rls == null || managed != rls) {
            return bean;
        }
        TenantGuc guc = tenantGuc.getIfUnique();
        if (guc == null) {
            return bean;
        }
        boolean present = manager.getTransactionExecutionListeners().stream()
                .anyMatch(RlsTransactionListener.class::isInstance);
        if (!present) {
            manager.addListener(new RlsTransactionListener(guc));
            log.debug("pg-rls: row-level security listener registered on transaction manager '" + beanName + "'");
        }
        return bean;
    }

    private static DataSource dataSourceOf(Object manager) {
        if (manager instanceof DataSourceTransactionManager jdbc) {
            return jdbc.getDataSource();
        }
        if (JPA_PRESENT) {
            return Jpa.dataSourceOf(manager);
        }
        return null;
    }

    /** Kept apart so that the JPA classes are only loaded when spring-orm is on the classpath. */
    private static final class Jpa {

        static DataSource dataSourceOf(Object manager) {
            return manager instanceof JpaTransactionManager jpa ? jpa.getDataSource() : null;
        }
    }
}
