package vn.truerate.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Tries each way a lender may have calculated the stated interest rate and asks one question per way:
 * "if the stated rate were applied like this, which principal would give exactly the total the borrower must repay?".
 * That implied principal is compared with the amount actually received plus the fees the user listed.
 * A gap that nothing explains is the hidden cost. Pure maths, no AI.
 */
public final class ScenarioCalculator {

    public enum Status {
        /** The way of calculating reproduces the numbers: nothing is hidden. */
        MATCH,
        /** The numbers match once the listed fees are counted as added to the principal. */
        FEES_EXPLAIN,
        /** The implied principal is larger than received plus listed fees: money is added to the principal that nobody explains. */
        HIDDEN,
        /** The implied principal is smaller: the lender charges less than the stated rate would give. */
        LOWER
    }

    public record Scenario(String code, String title, String how, long impliedBasisVnd, long gapVnd, long unexplainedVnd, Status status) {
    }

    private ScenarioCalculator() {
    }

    public static long tolerance(long principal) {
        return Math.max(2_000L, Math.round(principal * 0.003));
    }

    /** One payment at the end. rate = stated percent per year, days = term. */
    public static List<Scenario> oneTime(long principal, long totalRepay, int days, double ratePercent, long listedFees) {
        double r = ratePercent / 100.0;
        List<Scenario> out = new ArrayList<>();
        double simple = totalRepay / (1 + r * days / 365.0);
        out.add(make("SIMPLE", "Lãi đơn trên dư nợ", "Lãi = số tiền x lãi suất x số ngày / 365, không cộng dồn", simple, principal, listedFees));
        double compound = totalRepay / Math.pow(1 + r, days / 365.0);
        out.add(make("COMPOUND", "Lãi chồng lãi", "Lãi chưa trả được cộng vào gốc để tính lãi tiếp", compound, principal, listedFees));
        return out;
    }

    /** Equal monthly instalments of {@code payment}. */
    public static List<Scenario> monthly(long principal, long totalRepay, int months, double ratePercent, long listedFees) {
        double payment = (double) totalRepay / months;
        double i = ratePercent / 100.0 / 12.0;
        List<Scenario> out = new ArrayList<>();
        double annuity = i == 0 ? payment * months : payment * (1 - Math.pow(1 + i, -months)) / i;
        out.add(make("DECLINING", "Lãi trên dư nợ giảm dần", "Mỗi kỳ trả bằng nhau; lãi tính trên nợ gốc còn lại", annuity, principal, listedFees));
        double flat = totalRepay / (1 + ratePercent / 100.0 * months / 12.0);
        out.add(make("FLAT", "Lãi cố định trên gốc ban đầu", "Lãi tính trên toàn bộ gốc ban đầu cho cả kỳ vay, chia đều các kỳ", flat, principal, listedFees));
        return out;
    }

    private static Scenario make(String code, String title, String how, double basis, long principal, long listedFees) {
        long b = Math.round(basis);
        long gap = b - principal;
        long unexplained = gap - listedFees;
        long tol = tolerance(principal);
        Status st;
        if (Math.abs(unexplained) <= tol) {
            st = listedFees > tol ? Status.FEES_EXPLAIN : Status.MATCH;
        } else if (unexplained > 0) {
            st = Status.HIDDEN;
        } else {
            st = Status.LOWER;
        }
        return new Scenario(code, title, how, b, gap, unexplained, st);
    }

    /** The scenario whose unexplained gap is smallest. */
    public static Scenario best(List<Scenario> list) {
        Scenario best = list.get(0);
        for (Scenario s : list) {
            if (Math.abs(s.unexplainedVnd()) < Math.abs(best.unexplainedVnd())) {
                best = s;
            }
        }
        return best;
    }
}
