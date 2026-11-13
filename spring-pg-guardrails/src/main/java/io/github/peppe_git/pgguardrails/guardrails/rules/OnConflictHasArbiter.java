// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.rules;

import io.github.peppe_git.pgguardrails.guardrails.support.Catalog;
import io.github.peppe_git.pgguardrails.guardrails.support.Guard;
import io.github.peppe_git.pgguardrails.guardrails.support.SourceFiles;
import java.nio.file.Path;
import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Every {@code INSERT ... ON CONFLICT} written in the code names a conflict target the schema can arbitrate.
 *
 * <p>{@code ON CONFLICT (a, b)} requires a unique index (or primary key) on exactly those columns; otherwise
 * PostgreSQL rejects the whole statement ("there is no unique or exclusion constraint matching the ON CONFLICT
 * specification"). The failure is loud but late: not at compile time, not in unit tests, only when that branch runs
 * — and idempotency branches are the rarest ones (a retried webhook, a duplicate request, the first event of a new
 * period).
 *
 * <p>The statements are found in the source files (string literals included, Java/Kotlin string concatenation
 * joined); the arbiters come from the <b>catalog</b> of the database built by your migrations, so indexes created
 * by any means count. A partial unique index arbitrates only a target that carries a {@code WHERE}; a target with
 * expressions is reported as inconclusive. The table of a clause is the nearest preceding {@code INSERT INTO}.
 *
 * <p>Finding ids are {@code path#table(target)}, stable across edits of unrelated lines.
 */
public final class OnConflictHasArbiter extends Guard<OnConflictHasArbiter> {

    private static final Pattern ON_CONFLICT = Pattern.compile("\\bon\\s+conflict\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_INTO = Pattern.compile(
            "\\binsert\\s+into\\s+((?:\"[^\"]+\"|[a-z_][a-z0-9_$]*)(?:\\.(?:\"[^\"]+\"|[a-z_][a-z0-9_$]*))?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ON_CONSTRAINT = Pattern.compile("\\Gon\\s+constraint\\s+(\"[^\"]+\"|[a-z_][a-z0-9_$]*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DO_KEYWORD = Pattern.compile("\\Gdo\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHERE_KEYWORD = Pattern.compile("\\Gwhere\\b", Pattern.CASE_INSENSITIVE);
    /** Java/Kotlin string concatenation between two literals: {@code " + "}. */
    private static final Pattern JOINT = Pattern.compile("\"\\s*\\+\\s*\"");
    private static final int LOOKBEHIND = 4000;

    private final Catalog catalog;
    private final List<Path> roots;
    private Set<String> extensions = Set.of(".java", ".kt", ".sql");
    private Path base = Path.of("");

    private OnConflictHasArbiter(DataSource dataSource, List<Path> roots) {
        this.catalog = new Catalog(dataSource);
        this.roots = List.copyOf(roots);
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("name at least one source root");
        }
    }

    /** Statements under {@code sourceRoots}, arbiters from the database of {@code dataSource}. */
    public static OnConflictHasArbiter in(DataSource dataSource, Path... sourceRoots) {
        return new OnConflictHasArbiter(Objects.requireNonNull(dataSource, "dataSource"), Arrays.asList(sourceRoots));
    }

    /** File name suffixes to read (default {@code .java}, {@code .kt}, {@code .sql}). */
    public OnConflictHasArbiter extensions(String... suffixes) {
        this.extensions = Set.of(suffixes);
        return this;
    }

    /** Directory that finding ids are relative to (default: the working directory). */
    public OnConflictHasArbiter relativeTo(Path directory) {
        this.base = directory;
        return this;
    }

    @Override
    protected String name() {
        return "OnConflictHasArbiter";
    }

    @Override
    protected String rationale() {
        return "ON CONFLICT needs a unique index or constraint on exactly its target, or PostgreSQL rejects the "
                + "statement — only when that rare branch finally runs";
    }

    @Override
    protected String remedy() {
        return "add the unique index in a migration, or fix the target to match an existing one (repeat the WHERE "
                + "of a partial index); exempt by 'path#table(target)' with a reason";
    }

    @Override
    protected void evaluate(Collector collector) {
        for (Path file : SourceFiles.under(roots, extensions)) {
            boolean sql = file.getFileName().toString().endsWith(".sql");
            String text = SourceFiles.withoutComments(SourceFiles.read(file), sql);
            if (!sql) {
                text = joinLiterals(text);
            }
            Matcher m = ON_CONFLICT.matcher(text);
            while (m.find()) {
                collector.examined();
                inspect(collector, file, text, m.start(), m.end());
            }
        }
    }

    private void inspect(Collector collector, Path file, String text, int start, int end) {
        String where = id(file) + ":" + SourceFiles.lineOf(text, start);
        String table = tableBefore(text, start);
        int i = skipSpaces(text, end);
        String target;
        Clause clause;
        if (i < text.length() && text.charAt(i) == '(') {
            int close = closing(text, i);
            if (close < 0) {
                collector.inconclusive(where, "unbalanced parentheses after ON CONFLICT");
                return;
            }
            target = text.substring(i + 1, close);
            int after = skipSpaces(text, close + 1);
            boolean partial = WHERE_KEYWORD.matcher(text).region(after, text.length()).lookingAt();
            clause = new Clause(Kind.COLUMNS, target, partial);
        } else {
            Matcher constraint = ON_CONSTRAINT.matcher(text).region(i, text.length());
            if (constraint.lookingAt()) {
                target = constraint.group(1);
                clause = new Clause(Kind.CONSTRAINT, target, false);
            } else if (DO_KEYWORD.matcher(text).region(i, text.length()).lookingAt()) {
                target = "";
                clause = new Clause(Kind.NONE, "", false);
            } else {
                collector.inconclusive(where, "cannot read the conflict target (built dynamically?)");
                return;
            }
        }
        if (table == null) {
            collector.inconclusive(where, "no INSERT INTO found before this ON CONFLICT");
            return;
        }
        String id = id(file) + "#" + Catalog.identifier(table) + "(" + target.trim().replaceAll("\\s+", " ") + ")";
        Long relation = catalog.relation(table);
        if (relation == null) {
            collector.violation(id, "table " + table + " does not exist in the database (" + where + ")");
            return;
        }
        List<Index> indexes = indexes(relation);
        switch (clause.kind()) {
            case NONE -> {
                if (indexes.isEmpty()) {
                    collector.violation(id, "ON CONFLICT DO ... without a target on a table with no unique or "
                            + "exclusion constraint: it can never fire (" + where + ")");
                }
            }
            case CONSTRAINT -> {
                String wanted = Catalog.identifier(clause.target());
                List<String> names = catalog.query("select conname::text from pg_constraint "
                        + "where conrelid = ? and contype in ('p', 'u', 'x')", rs -> rs.getString(1), relation);
                if (!names.contains(wanted)) {
                    collector.violation(id, "names constraint " + wanted + ", which " + table + " does not have; it "
                            + "has " + names + " (" + where + ")");
                }
            }
            case COLUMNS -> columns(collector, id, where, table, clause, indexes);
            default -> throw new IllegalStateException();
        }
    }

    private static void columns(Collector collector, String id, String where, String table, Clause clause,
            List<Index> indexes) {
        Set<String> wanted = new TreeSet<>();
        for (String part : clause.target().split(",")) {
            String p = part.trim();
            if (p.isEmpty() || p.contains("(") || p.contains(" ")) {
                collector.inconclusive(id, "conflict target '" + clause.target() + "' contains an expression or "
                        + "an operator class: check its arbiter by hand (" + where + ")");
                return;
            }
            wanted.add(Catalog.identifier(p));
        }
        boolean partialOnly = false;
        for (Index index : indexes) {
            if (!index.unique() || index.expressions() || !index.columns().equals(wanted)) {
                continue;
            }
            if (!index.partial() || clause.partial()) {
                return;
            }
            partialOnly = true;
        }
        List<String> available = indexes.stream().filter(Index::unique).map(x -> x.columns()
                + (x.partial() ? " (partial)" : "")).toList();
        collector.violation(id, "no unique index on " + table + wanted + (partialOnly
                ? ": the only match is a partial index, and the target does not repeat its WHERE"
                : "; unique indexes are " + available) + " (" + where + ")");
    }

    private List<Index> indexes(long relation) {
        return catalog.query("""
                select array(select a.attname::text
                               from unnest(i.indkey::int2[]) k(attnum)
                               join pg_attribute a on a.attrelid = i.indrelid and a.attnum = k.attnum),
                       i.indisunique, i.indpred is not null, i.indexprs is not null
                  from pg_index i
                 where i.indrelid = ? and i.indisvalid and (i.indisunique or i.indisexclusion)
                """, rs -> {
                    Array cols = rs.getArray(1);
                    Set<String> names = new TreeSet<>();
                    for (Object o : (Object[]) cols.getArray()) {
                        names.add(String.valueOf(o));
                    }
                    return new Index(names, rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4));
                }, relation);
    }

    private String id(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        Path b = base.toAbsolutePath().normalize();
        return (abs.startsWith(b) ? b.relativize(abs) : abs).toString().replace('\\', '/');
    }

    private static String tableBefore(String text, int position) {
        int from = Math.max(0, position - LOOKBEHIND);
        Matcher m = INSERT_INTO.matcher(text).region(from, position);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    private static int skipSpaces(String text, int i) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int closing(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Joins {@code "a " + "b"} into {@code "a b"}, keeping the newlines so that line numbers do not move. */
    private static String joinLiterals(String text) {
        Matcher m = JOINT.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String newlines = m.group().replaceAll("[^\n]", "");
            m.appendReplacement(out, Matcher.quoteReplacement(newlines));
        }
        m.appendTail(out);
        return out.toString();
    }

    private enum Kind { COLUMNS, CONSTRAINT, NONE }

    private record Clause(Kind kind, String target, boolean partial) {
    }

    private record Index(Set<String> columns, boolean unique, boolean partial, boolean expressions) {
    }

    /** Visible for tests. */
    static List<String> clausesIn(String javaSource) {
        List<String> out = new ArrayList<>();
        Matcher m = ON_CONFLICT.matcher(joinLiterals(SourceFiles.withoutComments(javaSource, false)));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }
}
