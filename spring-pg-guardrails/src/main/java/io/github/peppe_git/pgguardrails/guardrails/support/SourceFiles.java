// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Source files read as text, for the few rules that must look at SQL written inside code. */
public final class SourceFiles {

    private SourceFiles() {
    }

    /** Regular files under the roots whose name ends with one of the extensions, sorted. Missing roots fail. */
    public static List<Path> under(List<Path> roots, Set<String> extensions) {
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                throw new AssertionError("source root " + root.toAbsolutePath() + " does not exist: a rule reading "
                        + "it would be green without having looked at anything");
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> extensions.stream().anyMatch(e -> p.getFileName().toString().endsWith(e)))
                        .sorted()
                        .forEach(out::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    public static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Replaces comments with spaces, keeping every newline so that offsets map to the same line numbers. Handles
     * {@code //} and block comments (Java, Kotlin) or {@code --} and block comments (SQL); string literals are kept.
     */
    public static String withoutComments(String text, boolean sql) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        char quote = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && !sql && next != 0) {
                    out.append(next);
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    quote = 0;
                }
                i++;
                continue;
            }
            boolean lineComment = sql ? (c == '-' && next == '-') : (c == '/' && next == '/');
            if (lineComment) {
                while (i < text.length() && text.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '*') {
                int end = text.indexOf("*/", i + 2);
                int stop = end < 0 ? text.length() : end + 2;
                for (int j = i; j < stop; j++) {
                    out.append(text.charAt(j) == '\n' ? '\n' : ' ');
                }
                i = stop;
                continue;
            }
            if (!sql && c == '\'') {
                // a Java char literal such as '"' must not open a string
                int end = next == '\\' ? text.indexOf('\'', i + 3) : text.indexOf('\'', i + 1);
                if (end > i && end - i <= 8) {
                    out.append(text, i, end + 1);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '"' || (sql && c == '\'')) {
                quote = c;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 1-based line of a character offset. */
    public static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
