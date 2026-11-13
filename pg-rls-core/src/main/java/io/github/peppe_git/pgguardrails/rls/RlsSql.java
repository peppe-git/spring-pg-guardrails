// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.rls;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SQL shipped with the library, rendered with concrete names.
 *
 * <p>The templates use <code>${name}</code> placeholders, the same syntax as Flyway placeholders, so they can be
 * copied into a migration as they are (and the placeholders configured with
 * {@code spring.flyway.placeholders.*}), or rendered here and pasted. Placeholders:
 * <ul>
 *   <li>{@code rls_schema} — schema of the helper functions (default {@code rls});
 *   <li>{@code tenant_guc}, {@code strict_guc}, {@code admin_read_guc} — see {@link RlsSettings};
 *   <li>{@code runtime_role} — the role the application connects as (runtime role script only);
 *   <li>{@code table}, {@code tenant_column}, {@code tenant_type} — inline policy template only.
 * </ul>
 */
public final class RlsSql {

    /** Helper functions and {@code enable_tenant_rls(regclass, name, boolean)}. */
    public static final String INSTALL = "rls-install.sql";

    /** {@code NOSUPERUSER NOBYPASSRLS} runtime role with default privileges. */
    public static final String RUNTIME_ROLE = "runtime-role.sql";

    /** One table protected by hand-written inline policies (no helper functions). */
    public static final String INLINE_POLICY = "rls-policy-inline-template.sql";

    /** Default schema for the helper functions. */
    public static final String DEFAULT_SCHEMA = "rls";

    private static final String BASE = "/io/github/peppe_git/pgguardrails/rls/sql/";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-z_]+)}");
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

    private RlsSql() {
    }

    /** Placeholders for {@code settings}, with the helper functions in {@code schema}. */
    public static Map<String, String> placeholders(RlsSettings settings, String schema) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("rls_schema", identifier(schema, "schema"));
        values.put("tenant_guc", settings.tenantGuc());
        values.put("strict_guc", settings.strictGuc());
        values.put("admin_read_guc", settings.adminReadGuc());
        return values;
    }

    /** The install script for the given settings. */
    public static String install(RlsSettings settings, String schema) {
        return render(INSTALL, placeholders(settings, schema));
    }

    /** The runtime role script; {@code role} must be a plain lower-case identifier. */
    public static String runtimeRole(String role, String schema) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("runtime_role", identifier(role, "role"));
        values.put("rls_schema", identifier(schema, "schema"));
        return render(RUNTIME_ROLE, values);
    }

    /**
     * Renders a bundled template. Every placeholder in the template must have a value: an unresolved
     * placeholder is an error, never left in the output.
     */
    public static String render(String template, Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        String text = read(template);
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) {
                throw new IllegalArgumentException("no value for placeholder ${" + m.group(1) + "} in " + template);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The raw template, placeholders included. */
    public static String read(String template) {
        try (InputStream in = RlsSql.class.getResourceAsStream(BASE + template)) {
            if (in == null) {
                throw new IllegalArgumentException("no bundled SQL template named " + template);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String identifier(String name, String what) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException(what + " must be a plain lower-case identifier: " + name);
        }
        return name;
    }
}
