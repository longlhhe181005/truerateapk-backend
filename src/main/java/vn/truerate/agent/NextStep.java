package vn.truerate.agent;

/** The agent may only pick one of these four values. */
public enum NextStep {
    SEEK_LICENSED_LENDER("Cân nhắc vay ở đơn vị được cấp phép và so sánh lãi suất trước khi quyết định."),
    REPORT_HARASSMENT("Nếu bị đòi nợ bằng đe dọa hoặc quấy rối, hãy lưu bằng chứng và phản ánh với cơ quan chức năng."),
    SEEK_LEGAL_ADVICE("Nên hỏi ý kiến người có chuyên môn pháp lý trước khi ký hoặc trả thêm."),
    NO_ACTION_NEEDED("Bạn không cần làm thêm gì ngay lúc này. Hãy giữ lại hợp đồng và biên lai.");

    private final String textVi;

    NextStep(String textVi) {
        this.textVi = textVi;
    }

    public String textVi() {
        return textVi;
    }
}
