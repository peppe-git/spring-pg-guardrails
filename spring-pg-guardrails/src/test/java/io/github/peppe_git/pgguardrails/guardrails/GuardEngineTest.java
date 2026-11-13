// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.peppe_git.pgguardrails.guardrails.support.Guard;
import io.github.peppe_git.pgguardrails.guardrails.support.GuardReport;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The two method principles every guard inherits: the floor, and exemptions that cannot rot. */
class GuardEngineTest {

    /** A guard that "examines" the given ids and objects to those starting with "bad". */
    static final class Toy extends Guard<Toy> {
        private final List<String> elements;
        private final List<String> undecided;

        Toy(List<String> elements, List<String> undecided) {
            this.elements = elements;
            this.undecided = undecided;
        }

        @Override
        protected String name() {
            return "Toy";
        }

        @Override
        protected String rationale() {
            return "toys must not be bad";
        }

        @Override
        protected String remedy() {
            return "fix the toy";
        }

        @Override
        protected void evaluate(Collector collector) {
            for (String e : elements) {
                collector.examined();
                if (e.startsWith("bad")) {
                    collector.violation(e, "is bad");
                }
            }
            undecided.forEach(u -> collector.inconclusive(u, "cannot tell"));
        }
    }

    @Test
    void aGuardThatLookedAtNothingIsNotGreen() {
        GuardReport report = new Toy(List.of(), List.of()).check();
        assertThat(report.isSatisfied()).isFalse();
        assertThatThrownBy(report::assertSatisfied).isInstanceOf(AssertionError.class)
                .hasMessageContaining("floor was not reached");
    }

    @Test
    void theFloorCanBeRaisedButNeverLoweredToZero() {
        assertThat(new Toy(List.of("a", "b"), List.of()).minimumExamined(3).check().isSatisfied()).isFalse();
        assertThat(new Toy(List.of("a", "b", "c"), List.of()).minimumExamined(3).check().isSatisfied()).isTrue();
        assertThatThrownBy(() -> new Toy(List.of(), List.of()).minimumExamined(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aViolationFailsTheGuardAndTheMessageSaysWhyAndHowToFix() {
        assertThatThrownBy(() -> new Toy(List.of("good", "bad-1"), List.of()).verify())
                .hasMessageContaining("bad-1: is bad")
                .hasMessageContaining("toys must not be bad")
                .hasMessageContaining("fix the toy");
    }

    @Test
    void anExemptionExcusesExactlyTheNamedFinding() {
        GuardReport report = new Toy(List.of("good", "bad-1"), List.of())
                .exempt("bad-1", "legacy, removed next quarter").check();
        assertThat(report.isSatisfied()).isTrue();
        assertThat(report.exempted()).containsEntry("bad-1", "legacy, removed next quarter");
    }

    @Test
    void anExemptionThatExcusesNothingFailsTheGuard() {
        GuardReport report = new Toy(List.of("good"), List.of()).exempt("bad-gone", "was legacy").check();
        assertThat(report.isSatisfied()).isFalse();
        assertThat(report.staleExemptions()).containsExactly("bad-gone");
    }

    @Test
    void anExemptionNeedsAReason() {
        assertThatThrownBy(() -> new Toy(List.of(), List.of()).exempt("bad-1", " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs a reason");
    }

    @Test
    void inconclusiveElementsFailUnlessExempted() {
        assertThat(new Toy(List.of("good"), List.of("odd")).check().isSatisfied()).isFalse();
        assertThat(new Toy(List.of("good"), List.of("odd")).exempt("odd", "checked by hand").check().isSatisfied())
                .isTrue();
    }
}
