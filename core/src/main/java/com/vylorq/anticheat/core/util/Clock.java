package com.vylorq.anticheat.core.util;

/** Time source. Swapped for a fake in tests so time-based rules can be checked without waiting. */
public interface Clock {
    long nowMillis();

    Clock SYSTEM = System::currentTimeMillis;

    /** Mutable clock for tests. */
    final class Manual implements Clock {
        private long now;

        public Manual(long start) {
            this.now = start;
        }

        @Override
        public long nowMillis() {
            return now;
        }

        public void advance(long millis) {
            now += millis;
        }

        public void set(long millis) {
            now = millis;
        }
    }
}
