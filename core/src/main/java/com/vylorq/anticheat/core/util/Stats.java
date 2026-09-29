package com.vylorq.anticheat.core.util;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Small statistics helpers used by aim and click analysis. */
public final class Stats {
    private Stats() {
    }

    public static double mean(double[] v) {
        if (v.length == 0) {
            return 0;
        }
        double s = 0;
        for (double d : v) {
            s += d;
        }
        return s / v.length;
    }

    public static double variance(double[] v) {
        if (v.length < 2) {
            return 0;
        }
        double m = mean(v);
        double s = 0;
        for (double d : v) {
            s += (d - m) * (d - m);
        }
        return s / (v.length - 1);
    }

    public static double stddev(double[] v) {
        return Math.sqrt(variance(v));
    }

    /** Coefficient of variation (stddev / mean). 0 when mean is 0. */
    public static double cv(double[] v) {
        double m = mean(v);
        return m == 0 ? 0 : stddev(v) / Math.abs(m);
    }

    public static double skewness(double[] v) {
        int n = v.length;
        if (n < 3) {
            return 0;
        }
        double m = mean(v);
        double sd = stddev(v);
        if (sd == 0) {
            return 0;
        }
        double s = 0;
        for (double d : v) {
            s += Math.pow((d - m) / sd, 3);
        }
        return s * n / ((double) (n - 1) * (n - 2));
    }

    /** Excess kurtosis (0 for a normal distribution). */
    public static double kurtosis(double[] v) {
        int n = v.length;
        if (n < 4) {
            return 0;
        }
        double m = mean(v);
        double m2 = 0;
        double m4 = 0;
        for (double d : v) {
            double x = d - m;
            m2 += x * x;
            m4 += x * x * x * x;
        }
        m2 /= n;
        m4 /= n;
        if (m2 == 0) {
            return 0;
        }
        return m4 / (m2 * m2) - 3.0;
    }

    /** Fraction of values that are distinct after rounding to {@code precision} decimals. */
    public static double distinctRatio(double[] v, int precision) {
        if (v.length == 0) {
            return 1;
        }
        double f = Math.pow(10, precision);
        Set<Long> set = new HashSet<>();
        for (double d : v) {
            set.add(Math.round(d * f));
        }
        return set.size() / (double) v.length;
    }

    public static double[] toArray(Collection<? extends Number> values) {
        double[] out = new double[values.size()];
        int i = 0;
        for (Number n : values) {
            out[i++] = n.doubleValue();
        }
        return out;
    }

    /** Greatest common divisor of two floating values, to the given tolerance. Used for rotation "sensitivity" checks. */
    public static double gcd(double a, double b, double epsilon) {
        a = Math.abs(a);
        b = Math.abs(b);
        if (a < b) {
            double t = a;
            a = b;
            b = t;
        }
        int guard = 0;
        while (b > epsilon && guard++ < 100) {
            double t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** Smallest signed difference between two angles in degrees (-180..180]. */
    public static double wrapDegrees(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }
}
