// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.pgguardrails.guardrails.rules.MigrationChecksumLedger;
import io.github.peppe_git.pgguardrails.guardrails.support.Finding;
import io.github.peppe_git.pgguardrails.guardrails.support.GuardReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationChecksumLedgerTest {

    @TempDir
    Path dir;

    private Path migrations;
    private Path ledger;

    @BeforeEach
    void twoMigrationsAndTheirLedger() throws IOException {
        migrations = Files.createDirectories(dir.resolve("db/migration"));
        ledger = dir.resolve("migrations.sha256");
        Files.writeString(migrations.resolve("V1__init.sql"), "create table a (id int);\n");
        Files.writeString(migrations.resolve("V2__more.sql"), "-- adds b\ncreate table b (id int);\n");
        Files.writeString(migrations.resolve("R__views.sql"), "create or replace view v as select 1;\n");
        Files.writeString(ledger, MigrationChecksumLedger.of(migrations, ledger).render());
    }

    private GuardReport check() {
        return MigrationChecksumLedger.of(migrations, ledger).check();
    }

    @Test
    void anUntouchedHistoryPassesAndRepeatableMigrationsAreIgnored() {
        GuardReport report = check();
        assertThat(report.isSatisfied()).as(report.describe()).isTrue();
        assertThat(report.examined()).isEqualTo(2);
    }

    @Test
    void editingOnlyACommentOfAnAppliedMigrationFails() throws IOException {
        Files.writeString(migrations.resolve("V2__more.sql"), "-- adds table b\ncreate table b (id int);\n");
        assertThat(check().violations()).singleElement().satisfies(f -> {
            assertThat(f.id()).isEqualTo("V2__more.sql");
            assertThat(f.message()).contains("CHANGED");
        });
    }

    @Test
    void deletingAnAppliedMigrationFails() throws IOException {
        Files.delete(migrations.resolve("V1__init.sql"));
        assertThat(check().violations()).extracting(Finding::id).containsExactly("V1__init.sql");
    }

    @Test
    void aNewMigrationFailsUntilItsLineIsAddedAndTheMessageGivesTheLine() throws IOException {
        Files.writeString(migrations.resolve("V3__c.sql"), "create table c (id int);\n");
        Finding finding = check().violations().get(0);
        assertThat(finding.message()).contains("V3__c.sql ");
        String line = finding.message().substring(finding.message().indexOf("V3__c.sql "));
        Files.writeString(ledger, Files.readString(ledger) + line + "\n");
        assertThat(check().isSatisfied()).isTrue();
    }

    @Test
    void windowsLineEndingsDoNotCountAsAChange() throws IOException {
        Files.writeString(migrations.resolve("V2__more.sql"), "-- adds b\r\ncreate table b (id int);\r\n");
        assertThat(check().isSatisfied()).isTrue();
    }
}
