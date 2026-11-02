package vn.truerate.core;

/** The four numbers a user types. No personal identity data is collected. */
public record LoanInput(Long principalVnd, Long totalRepayVnd, Integer termDays, String lenderName) {
}
