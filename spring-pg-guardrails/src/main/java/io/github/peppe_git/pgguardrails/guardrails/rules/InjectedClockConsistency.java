// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.rules;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import io.github.peppe_git.pgguardrails.guardrails.support.Guard;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A class that has a {@code java.time.Clock} field reads the time only from it.
 *
 * <p>An injected clock is a seam for tests that move time. It works only if it is complete: one {@code Instant.now()}
 * left inline in a class that also holds a {@code Clock} keeps the time-travel test green while half the logic looks
 * at another clock. That is the worst way to break — not a red test, but a green one that no longer proves what it
 * says.
 *
 * <p>The scope is derived, not listed: every class with a {@code Clock} field. Classes without one are left alone;
 * the rule is about consistency of those that asked for the seam. Flagged: {@code X.now()} without a {@code Clock}
 * argument for the {@code java.time} types, {@code System.currentTimeMillis()}, {@code new java.util.Date()}, and
 * {@code Clock.system*()} outside constructors (in a constructor it is the default value of the field, not a read).
 * {@code System.nanoTime()} is a stopwatch, not a time of day, and is allowed.
 */
public final class InjectedClockConsistency extends Guard<InjectedClockConsistency> {

    private static final String CLOCK = "java.time.Clock";

    private static final Set<String> TIME_SOURCES = Set.of("java.time.Instant", "java.time.LocalDate",
            "java.time.LocalDateTime", "java.time.LocalTime", "java.time.OffsetDateTime", "java.time.OffsetTime",
            "java.time.ZonedDateTime", "java.time.YearMonth", "java.time.Year", "java.time.MonthDay");

    private static final Set<String> SYSTEM_CLOCKS = Set.of("systemUTC", "systemDefaultZone", "system");

    private final JavaClasses classes;

    private InjectedClockConsistency(JavaClasses classes) {
        this.classes = Objects.requireNonNull(classes, "classes");
    }

    public static InjectedClockConsistency in(JavaClasses classes) {
        return new InjectedClockConsistency(classes);
    }

    @Override
    protected String name() {
        return "InjectedClockConsistency";
    }

    @Override
    protected String rationale() {
        return "a class with an injected Clock that also reads the system time makes time-travel tests pass while "
                + "part of the logic ignores them";
    }

    @Override
    protected String remedy() {
        return "pass the clock: Instant.now(clock), LocalDate.now(clock), clock.millis() — or remove the Clock field "
                + "if the class does not need the seam";
    }

    @Override
    protected void evaluate(Collector collector) {
        for (JavaClass clazz : classes) {
            if (clazz.getFields().stream().noneMatch(f -> CLOCK.equals(f.getRawType().getName()))) {
                continue;
            }
            collector.examined();
            Set<String> reads = new TreeSet<>();
            for (JavaMethodCall call : clazz.getMethodCallsFromSelf()) {
                if (readsSystemTime(call)) {
                    reads.add(describe(call));
                }
            }
            for (JavaConstructorCall call : clazz.getConstructorCallsFromSelf()) {
                if ("java.util.Date".equals(call.getTargetOwner().getName())
                        && call.getTarget().getRawParameterTypes().isEmpty()) {
                    reads.add(describe(call));
                }
            }
            if (!reads.isEmpty()) {
                collector.violation(clazz.getName(), "has a Clock field but reads the system time: " + reads);
            }
        }
    }

    private static boolean readsSystemTime(JavaMethodCall call) {
        String owner = call.getTargetOwner().getName();
        String name = call.getName();
        if ("java.lang.System".equals(owner) && "currentTimeMillis".equals(name)) {
            return true;
        }
        if (CLOCK.equals(owner) && SYSTEM_CLOCKS.contains(name)) {
            return !"<init>".equals(call.getOrigin().getName());
        }
        if (!TIME_SOURCES.contains(owner) || !"now".equals(name)) {
            return false;
        }
        return call.getTarget().getRawParameterTypes().stream().noneMatch(p -> CLOCK.equals(p.getName()));
    }

    private static String describe(JavaCodeUnitAccess<?> call) {
        return call.getTargetOwner().getSimpleName() + "." + call.getName() + "() at " + call.getSourceCodeLocation();
    }
}
