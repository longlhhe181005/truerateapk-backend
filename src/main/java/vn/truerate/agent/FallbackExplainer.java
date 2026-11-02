package vn.truerate.agent;

import java.util.Locale;

/** Fixed Vietnamese text used when the AI is off or its reply fails validation. */
public final class FallbackExplainer {

    private FallbackExplainer() {
    }

    public static AgentReply explain(AgentContext c, String note) {
        String extra = String.format(Locale.US, "%,d", c.totalRepayVnd() - c.principalVnd()).replace(',', '.');
        String base = "Bạn nhận " + vnd(c.principalVnd()) + " và phải trả " + vnd(c.totalRepayVnd()) + " sau " + c.termDays()
                + " ngày, tức trả thêm " + extra + "đ, bằng " + percentText(c.termPercent()) + "% số tiền nhận được trong " + c.termDays() + " ngày đó.";
        String ref20 = "mức " + vnd(c.cap20Vnd()) + ", là mốc tham khảo 20%/năm tính cho " + c.termDays() + " ngày vay";
        String comparison;
        NextStep step;
        switch (c.riskCode()) {
            case "LICENSED_LOW" -> {
                comparison = "Tiền lãi này không vượt " + ref20 + ".";
                step = NextStep.NO_ACTION_NEEDED;
            }
            case "LICENSED_HIGH" -> {
                comparison = "Tiền lãi này cao hơn " + ref20 + ". Mốc này có áp dụng cho công ty tài chính được cấp phép hay không cần luật sư xác nhận.";
                step = NextStep.SEEK_LICENSED_LENDER;
            }
            case "LICENSED_VERY_HIGH" -> {
                comparison = "Tiền lãi này vượt xa " + ref20 + ". Hãy kiểm tra lại các con số bạn đã nhập.";
                step = NextStep.SEEK_LEGAL_ADVICE;
            }
            case "SERIOUS" -> {
                comparison = "Tính cho " + c.termDays() + " ngày vay, tiền lãi này vượt cả mốc 20%/năm (" + vnd(c.cap20Vnd()) + ") và mốc 100%/năm (" + vnd(c.cap100Vnd())
                        + "), và không tìm thấy bên cho vay trong danh sách cấp phép. Đây là dấu hiệu cần đặc biệt thận trọng.";
                step = NextStep.SEEK_LEGAL_ADVICE;
            }
            case "RISK_SIGNALS" -> {
                comparison = "Bạn đã đánh dấu các dấu hiệu đáng ngờ và chưa xác minh được bên cho vay. Lãi suất thấp không có nghĩa là an toàn.";
                step = NextStep.SEEK_LEGAL_ADVICE;
            }
            case "NOT_FOUND" -> {
                comparison = "Chưa tìm thấy bên cho vay trong danh sách cấp phép hiện có, nên chưa thể xác minh tư cách pháp lý của họ.";
                step = NextStep.SEEK_LEGAL_ADVICE;
            }
            default -> {
                comparison = "Chưa xác định được bên cho vay nên chưa thể đối chiếu đầy đủ với quy định.";
                step = NextStep.SEEK_LEGAL_ADVICE;
            }
        }
        return new AgentReply(base, comparison, step, "offline", note);
    }

    static String percentText(double v) {
        String s = v >= 100 ? String.format(Locale.US, "%,.0f", v) : String.format(Locale.US, "%,.1f", v);
        return s.replace(',', '#').replace('.', ',').replace('#', '.');
    }

    private static String vnd(long v) {
        return String.format(Locale.US, "%,d", v).replace(',', '.') + "đ";
    }
}
