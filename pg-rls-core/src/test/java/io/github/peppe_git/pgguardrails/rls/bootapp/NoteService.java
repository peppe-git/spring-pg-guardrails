// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls.bootapp;

import io.github.peppe_git.pgguardrails.rls.TenantContext;
import io.github.peppe_git.pgguardrails.rls.TenantGuc;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoteService {

    private final NoteRepository notes;
    private final TenantGuc tenantGuc;

    public NoteService(NoteRepository notes, TenantGuc tenantGuc) {
        this.notes = notes;
        this.tenantGuc = tenantGuc;
    }

    @Transactional
    public Note add(String body) {
        return notes.save(new Note(TenantContext.current(UUID.class).orElseThrow(), body));
    }

    @Transactional(readOnly = true)
    public long countAmong(Collection<UUID> tenants) {
        return notes.countByTenantIdIn(tenants);
    }

    @Transactional(readOnly = true)
    public long countAll() {
        return notes.count();
    }

    @Transactional(readOnly = true)
    public long countAmongAsPlatformAdmin(Collection<UUID> tenants) {
        tenantGuc.applyAdminRead();
        return notes.countByTenantIdIn(tenants);
    }

    @Transactional
    public long countAmongAsPlatformAdminInReadWrite(Collection<UUID> tenants) {
        tenantGuc.applyAdminRead();
        return notes.countByTenantIdIn(tenants);
    }
}
