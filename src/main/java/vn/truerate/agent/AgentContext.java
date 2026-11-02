package vn.truerate.agent;

/**
 * Facts already decided by code. The agent receives these and must not recompute or change them.
 * termPercent covers the loan's own term and is never annualised; cap20Vnd and cap100Vnd are the
 * yearly reference levels converted to that same term.
 */
public record AgentContext(
        double termPercent,
        long cap20Vnd,
        long cap100Vnd,
        String riskCode,
        String riskLevel,
        long principalVnd,
        long totalRepayVnd,
        int termDays,
        String lenderName,
        String licenceStatus) {
}
