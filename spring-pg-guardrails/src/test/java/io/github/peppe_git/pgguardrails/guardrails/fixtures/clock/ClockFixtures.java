// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.fixtures.clock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;

/** Fixtures for InjectedClockConsistency: one violation, three clean. */
public final class ClockFixtures {

    private ClockFixtures() {
    }

    public static class Consistent {
        private final Clock clock;

        public Consistent(Clock clock) {
            this.clock = clock;
        }

        public boolean expired(Instant deadline) {
            return Instant.now(clock).isAfter(deadline) && LocalDate.now(clock).getYear() > 0;
        }
    }

    public static class Leaky {
        private final Clock clock;

        public Leaky(Clock clock) {
            this.clock = clock;
        }

        public boolean expired(Instant deadline) {
            return Instant.now().isAfter(deadline) || System.currentTimeMillis() < 0 || new Date().getTime() < 0
                    || clock.millis() < 0;
        }
    }

    public static class DefaultsInConstructor {
        private final Clock clock;

        public DefaultsInConstructor() {
            this.clock = Clock.systemUTC();
        }

        public Instant now() {
            return clock.instant();
        }
    }

    public static class NoSeam {
        public Instant now() {
            return Instant.now();
        }
    }
}
