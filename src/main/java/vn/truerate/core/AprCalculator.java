package vn.truerate.core;

/**
 * Pure maths. No AI.
 * Simple annual rate = (R - P) / P * 365 / d. Same basis as the legal reference levels (20% and 100% per year), used for labels.
 * Compound rate = (1 + (R - P) / P)^(365 / d) - 1. Assumes the loan is rolled over and interest is added to principal; shown as extra information.
 */
public final class AprCalculator {

    /** Values above this are shown as "more than ..." in the UI. */
    public static final double CAP_PERCENT = 1_000_000.0;

    private AprCalculator() {
    }

    public static double simplePercent(long principal, long totalRepay, int days) {
        double v = (double) (totalRepay - principal) / principal * 365.0 / days * 100.0;
        if (Double.isNaN(v) || Double.isInfinite(v) || v > CAP_PERCENT) {
            return CAP_PERCENT;
        }
        return v;
    }

    public static double aprPercent(long principal, long totalRepay, int days) {
        double ratio = (double) (totalRepay - principal) / principal;
        double apr = (Math.pow(1.0 + ratio, 365.0 / days) - 1.0) * 100.0;
        if (Double.isNaN(apr) || Double.isInfinite(apr) || apr > CAP_PERCENT) {
            return CAP_PERCENT;
        }
        return apr;
    }

    /**
     * Equal monthly instalments: the borrower receives {@code principal} today and pays {@code payment} at the end of
     * each of {@code months} months. Returns the monthly rate i that makes the payments worth exactly the amount received
     * (the internal rate of return of the cash flows). Fees taken out before disbursement are therefore included.
     */
    public static double monthlyRate(long principal, double payment, int months) {
        double lo = 0.0, hi = 10.0;
        for (int k = 0; k < 200; k++) {
            double mid = (lo + hi) / 2;
            double pv = mid == 0 ? payment * months : payment * (1 - Math.pow(1 + mid, -months)) / mid;
            if (pv > principal) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    /** Simple annual rate for instalments: monthly rate x 12, the same basis as the legal reference levels. */
    public static double instalmentSimplePercent(long principal, long totalRepay, int months) {
        double i = monthlyRate(principal, (double) totalRepay / months, months);
        return Math.min(i * 12 * 100, CAP_PERCENT);
    }

    /** Effective annual rate if monthly interest compounded: (1 + i)^12 - 1. */
    public static double instalmentCompoundPercent(long principal, long totalRepay, int months) {
        double i = monthlyRate(principal, (double) totalRepay / months, months);
        double v = (Math.pow(1 + i, 12) - 1) * 100;
        return Double.isFinite(v) ? Math.min(v, CAP_PERCENT) : CAP_PERCENT;
    }

    /** Interest allowed for a single payment after {@code days} at a yearly reference rate (simple interest). */
    public static long referenceCostOneTime(long principal, int days, double yearlyPercent) {
        return Math.round(principal * yearlyPercent / 100.0 * days / 365.0);
    }

    /** Total interest of equal monthly instalments at a yearly reference rate (declining balance). */
    public static long referenceCostInstalments(long principal, int months, double yearlyPercent) {
        double i = yearlyPercent / 100.0 / 12.0;
        double payment = i == 0 ? (double) principal / months : principal * i / (1 - Math.pow(1 + i, -months));
        return Math.round(payment * months - principal);
    }

    public static boolean isCapped(double aprPercent) {
        return aprPercent >= CAP_PERCENT;
    }
}
