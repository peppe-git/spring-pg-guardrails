// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

import org.springframework.core.task.TaskDecorator;

/**
 * Propagates the {@link TenantContext} of the submitting thread to the task, and restores the executing thread
 * afterwards. Set it on a {@code ThreadPoolTaskExecutor} ({@code setTaskDecorator}).
 */
public final class TenantContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        return TenantContext.wrap(runnable);
    }
}
