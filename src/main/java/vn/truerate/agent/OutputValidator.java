package vn.truerate.agent;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Last defence layer: checks the agent's reply before it reaches the user. Any failure means fallback text. */
public final class OutputValidator {

    public static final int MAX_EXPLANATION = 700;
    public static final int MAX_COMPARISON = 350;

    /** Never allowed. */
    private static final List<String> ALWAYS_BANNED = List.of(
            "chắc chắn phạm tội", "chắc chắn vi phạm", "luật sư ai", "ai luật sư", "bùng nợ", "bùng tiền",
            "không cần trả", "không phải trả nợ", "đã kiểm chứng 100%", "chính xác tuyệt đối");

    /** Allowed only when the code already decided the label is SERIOUS. */
    private static final List<String> SERIOUS_ONLY = List.of(
            "tín dụng đen", "bất hợp pháp", "lừa đảo", "phạm pháp", "vi phạm pháp luật", "cho vay nặng lãi");

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d.,]*");

    private OutputValidator() {
    }

    /** Any amount of 4+ digits in the text must be one of the amounts code already computed (years 1900-2100 are allowed). */
    static String unknownAmount(String text, AgentContext ctx) {
        // a HashSet, not Set.of: principal and extra cost can be equal (repay = 2 x principal) and Set.of rejects duplicates
        Set<Long> allowed = new java.util.HashSet<>(List.of(ctx.principalVnd(), ctx.totalRepayVnd(), ctx.totalRepayVnd() - ctx.principalVnd(),
                ctx.cap20Vnd(), ctx.cap100Vnd()));
        return unknownAmount(text, allowed);
    }

    static boolean hasUnknownAmount(String text, Set<Long> allowed) {
        return unknownAmount(text, allowed) != null;
    }

    /** @return the first amount not in the allowed set, as written in the text, or null when there is none. */
    static String unknownAmount(String text, Set<Long> allowed) {
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String token = m.group();
            int end = m.end();
            if (end < text.length() && text.charAt(end) == '%') {
                continue;
            }
            token = token.replaceAll("[.,]+$", "");
            String digits = token.replaceAll("[^0-9]", "");
            if (digits.length() < 4 || digits.length() > 15) {
                continue;
            }
            long v = Long.parseLong(digits);
            boolean year = token.equals(digits) && v >= 1900 && v <= 2100;
            if (!year && !allowed.contains(v)) {
                return token;
            }
        }
        return null;
    }

    /** Wording that must never appear in free text written for the user (used where the label is not SERIOUS). */
    static String bannedWording(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        for (String b : ALWAYS_BANNED) {
            if (t.contains(b)) {
                return "banned phrase";
            }
        }
        for (String b : SERIOUS_ONLY) {
            if (t.contains(b)) {
                return "label wording not allowed here";
            }
        }
        return null;
    }

    /** A phone number (starts with 0) or a 12-digit ID in free text. Amounts such as 100.000.000 do not match. */
    static boolean looksLikeContact(String text) {
        return Pattern.compile("(?<![\\d.,])0\\d{9,10}(?![\\d.,])|(?<!\\d)0\\d{2,3}[ .-]\\d{3}[ .-]\\d{3,4}(?!\\d)|(?<![\\d.,])\\d{12}(?![\\d.,])")
                .matcher(text).find();
    }

    /** @return null when valid, otherwise a short reason. */
    public static String check(String explanation, String comparison, NextStep step, AgentContext ctx) {
        if (explanation == null || explanation.isBlank()) {
            return "empty explanation";
        }
        if (comparison == null || comparison.isBlank()) {
            return "empty comparison";
        }
        if (step == null) {
            return "invalid next step";
        }
        if (explanation.length() > MAX_EXPLANATION || comparison.length() > MAX_COMPARISON) {
            return "too long";
        }
        String text = (explanation + " " + comparison).toLowerCase(Locale.ROOT);
        for (String banned : ALWAYS_BANNED) {
            if (text.contains(banned)) {
                return "banned phrase";
            }
        }
        if (!"SERIOUS".equals(ctx.riskCode())) {
            for (String banned : SERIOUS_ONLY) {
                if (text.contains(banned)) {
                    return "label wording not allowed for this risk level";
                }
            }
        }
        String unknown = unknownAmount(explanation + " " + comparison, ctx);
        if (unknown != null) {
            return "amount not from the computed facts: " + unknown;
        }
        boolean worrying = "serious".equals(ctx.riskLevel()) || "high".equals(ctx.riskLevel());
        if (worrying && step == NextStep.NO_ACTION_NEEDED) {
            return "next step contradicts risk level";
        }
        return null;
    }
}
