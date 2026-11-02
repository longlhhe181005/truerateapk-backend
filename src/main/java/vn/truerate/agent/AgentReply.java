package vn.truerate.agent;

/** mode: "claude" when the model wrote the text, "offline" when a fixed template was used. */
public record AgentReply(String explanation, String legalComparison, NextStep nextStep, String mode, String note) {
}
