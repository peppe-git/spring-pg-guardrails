// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.rules;

import io.github.peppe_git.pgguardrails.guardrails.support.Guard;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A migration, once written, never changes — not even a comment.
 *
 * <p>Flyway stores a checksum of every applied migration and refuses to start when a file no longer matches it. In
 * production there is usually no {@code repair}: an edited comment in an applied migration stops the next deployment
 * until someone fixes the history table by hand. The edit is invisible in CI, which migrates an empty database.
 *
 * <p>The rule keeps a <b>ledger</b> file in the repository, one {@code <file name> <sha-256>} line per migration,
 * and compares. It does not read git history: CI checkouts are often shallow, and a guard that reads a truncated
 * history answers "no change" to every question. Line endings and a UTF-8 BOM are normalised before hashing, so a
 * checkout with different line endings does not trip it.
 *
 * <p>Allowed change to the ledger: <b>adding</b> the line of a new migration (the failure message prints it). A
 * mismatch is never fixed by editing the line; it is fixed by a new migration.
 */
public final class MigrationChecksumLedger extends Guard<MigrationChecksumLedger> {

    private final Path migrations;
    private final Path ledger;
    private Pattern fileName = Pattern.compile("V[0-9]+(?:[._][0-9]+)*__.+\\.sql");

    private MigrationChecksumLedger(Path migrations, Path ledger) {
        this.migrations = Objects.requireNonNull(migrations, "migrations");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    /** Migrations in {@code migrationsDirectory}, ledger in {@code ledgerFile}. */
    public static MigrationChecksumLedger of(Path migrationsDirectory, Path ledgerFile) {
        return new MigrationChecksumLedger(migrationsDirectory, ledgerFile);
    }

    /** Which file names are immutable migrations (default: Flyway versioned, {@code V<n>__<desc>.sql}). */
    public MigrationChecksumLedger fileNames(String regex) {
        this.fileName = Pattern.compile(regex);
        return this;
    }

    @Override
    protected String name() {
        return "MigrationChecksumLedger";
    }

    @Override
    protected String rationale() {
        return "Flyway refuses to start when an applied migration changed by a single byte, and production usually "
                + "has no repair: the next deployment stops";
    }

    @Override
    protected String remedy() {
        return "never edit the ledger line of an existing migration: restore the file and write a new migration; "
                + "for a new migration, add the line printed above";
    }

    @Override
    protected void evaluate(Collector collector) {
        Map<String, String> expected = readLedger();
        Map<String, String> actual = new LinkedHashMap<>();
        for (Path file : migrationFiles()) {
            actual.put(file.getFileName().toString(), sha256(file));
        }
        collector.examined(actual.size());
        actual.forEach((name, hash) -> {
            String recorded = expected.get(name);
            if (recorded == null) {
                collector.violation(name, "new migration not in the ledger; add this line to " + ledger + ": "
                        + name + " " + hash);
            } else if (!recorded.equals(hash)) {
                collector.violation(name, "CHANGED after it was recorded (ledger " + recorded + ", file " + hash
                        + "): every database that applied it will refuse to start");
            }
        });
        for (String name : new TreeSet<>(expected.keySet())) {
            if (!actual.containsKey(name)) {
                collector.violation(name, "in the ledger but no longer on disk: deleting an applied migration is "
                        + "the same as changing it");
            }
        }
    }

    /** The ledger content for the migrations currently on disk, to bootstrap the file once. */
    public String render() {
        StringBuilder out = new StringBuilder("# <migration file> <sha-256 of its content, line endings normalised>\n");
        for (Path file : migrationFiles()) {
            out.append(file.getFileName()).append(' ').append(sha256(file)).append('\n');
        }
        return out.toString();
    }

    private List<Path> migrationFiles() {
        if (!Files.isDirectory(migrations)) {
            throw new AssertionError("migrations directory " + migrations.toAbsolutePath() + " does not exist");
        }
        try (Stream<Path> files = Files.list(migrations)) {
            return files.filter(p -> fileName.matcher(p.getFileName().toString()).matches()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, String> readLedger() {
        if (!Files.isRegularFile(ledger)) {
            throw new AssertionError("ledger " + ledger.toAbsolutePath() + " does not exist; create it with "
                    + "MigrationChecksumLedger.render()");
        }
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (String raw : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length != 2) {
                    throw new AssertionError("malformed ledger line in " + ledger + ": '" + raw + "'");
                }
                if (out.put(parts[0], parts[1]) != null) {
                    throw new AssertionError("migration listed twice in " + ledger + ": " + parts[0]);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    static String sha256(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) {
                text = text.substring(1);
            }
            text = text.replace("\r\n", "\n").replace('\r', '\n');
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
