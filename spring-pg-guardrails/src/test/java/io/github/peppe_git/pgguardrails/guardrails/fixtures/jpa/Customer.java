// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.fixtures.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Known defects, matched against the schema created by the test:
 * <ul>
 *   <li>{@code nickname}: mapped non-null, column nullable;
 *   <li>{@code loginCount}: primitive over a nullable column;
 *   <li>{@code tier}: the CHECK lacks {@code PLATINUM} and has {@code LEGACY};
 * </ul>
 * and correct mappings for the rest.
 */
@Entity
@Table(name = "jpa_customers")
public class Customer {

    public enum Status { ACTIVE, SUSPENDED }

    public enum Tier { FREE, GOLD, PLATINUM }

    public enum Mood { HAPPY, SAD }

    @Id
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String nickname;

    @Column(name = "login_count")
    private int loginCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    private Tier tier;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "jpa_mood")
    private Mood mood;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "status_ordinal")
    private Status statusOrdinal;

    protected Customer() {
    }
}
