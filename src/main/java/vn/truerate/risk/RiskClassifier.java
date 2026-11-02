package vn.truerate.risk;

import org.springframework.stereotype.Component;
import vn.truerate.license.LicenseRegistry.Status;

/**
 * The labelling matrix (business rule BR-02). Decided by code, never by AI.
 * A high rate alone is never called illegal. "Serious" needs BOTH: no licence found AND rate above 100% per year.
 */
@Component
public class RiskClassifier {

    public static final double CIVIL_REFERENCE_PERCENT = 20.0;
    public static final double CRIMINAL_REFERENCE_PERCENT = 100.0;

    public enum Code {
        LICENSED_LOW, LICENSED_HIGH, LICENSED_VERY_HIGH, NOT_FOUND, SERIOUS, UNDETERMINED, RISK_SIGNALS
    }

    /** level: low, caution, high, serious, unknown (the UI maps these to colours). */
    public record Label(Code code, String level, String title, String message) {
    }

    /**
     * Warning signs the user ticked (fee before disbursement, contact-list access, name mismatch, harassment) matter even when
     * the rate is low: a low rate does not make an unverified lender safe.
     */
    public Label classify(Status licence, double aprPercent, int signalCount) {
        Label base = classify(licence, aprPercent);
        if (signalCount > 0 && licence != Status.LICENSED && base.code() != Code.SERIOUS) {
            return new Label(Code.RISK_SIGNALS, "high", "Có dấu hiệu rủi ro, dù lãi suất " + (aprPercent <= 100 ? "chưa quá cao" : "cao"),
                    "Bạn đã đánh dấu " + signalCount + " dấu hiệu đáng ngờ và chưa xác minh được bên cho vay trong danh sách cấp phép. "
                            + "Lãi suất thấp không có nghĩa là an toàn: một số bên thu phí trước, đòi quyền truy cập danh bạ hoặc mạo danh tên khác. "
                            + "Hãy dừng lại, kiểm tra tên pháp lý và mã số doanh nghiệp, và đừng gửi thêm giấy tờ hay tiền trước khi xác minh.");
        }
        return base;
    }

    public Label classify(Status licence, double aprPercent) {
        if (licence == Status.UNDETERMINED) {
            return new Label(Code.UNDETERMINED, "unknown", "Chưa xác định được bên cho vay",
                    "Tên bạn nhập chưa khớp chắc chắn với danh sách cấp phép (có thể gần giống một đơn vị khác hoặc gõ sai). "
                            + "Hãy nhập lại đúng tên pháp lý như ghi trên hợp đồng, hoặc hỏi bên cho vay mã số doanh nghiệp.");
        }
        if (licence == Status.LICENSED) {
            if (aprPercent <= CIVIL_REFERENCE_PERCENT) {
                return new Label(Code.LICENSED_LOW, "low", "Có trong danh sách cấp phép · lãi thấp",
                        "Đơn vị có trong danh sách cấp phép và lãi suất thực nằm trong mức tham khảo 20%/năm.");
            }
            if (aprPercent <= CRIMINAL_REFERENCE_PERCENT) {
                return new Label(Code.LICENSED_HIGH, "caution", "Có trong danh sách cấp phép · lãi cao hơn mức tham khảo",
                        "Đơn vị có trong danh sách cấp phép nhưng lãi suất thực cao hơn mức tham khảo 20%/năm. "
                                + "Hãy so sánh với các lựa chọn khác trước khi vay.");
            }
            return new Label(Code.LICENSED_VERY_HIGH, "high", "Có trong danh sách cấp phép · lãi rất cao",
                    "Lãi suất thực rất cao so với mốc tham khảo. Hãy đối chiếu các con số với hợp đồng gốc (có thể nhập nhầm), và hỏi bên cho vay bằng văn bản về từng khoản phí.");
        }
        if (aprPercent > CRIMINAL_REFERENCE_PERCENT) {
            return new Label(Code.SERIOUS, "serious", "Cảnh báo nghiêm trọng",
                    "Không tìm thấy bên cho vay trong danh sách cấp phép và lãi suất thực vượt 100%/năm. "
                            + "Đây là dấu hiệu rủi ro nghiêm trọng, nhưng chỉ cơ quan có thẩm quyền mới kết luận được. "
                            + "Một số doanh nghiệp hợp pháp (ví dụ cầm đồ) hoạt động ngoài danh sách tổ chức tín dụng, nên hãy kiểm tra giấy đăng ký kinh doanh. "
                            + "Hãy cân nhắc kỹ trước khi vay hoặc trả thêm.");
        }
        return new Label(Code.NOT_FOUND, "caution", "Chưa thấy trong danh sách cấp phép",
                "Không tìm thấy bên cho vay trong danh sách cấp phép hiện có. Hãy kiểm tra lại tên hoặc hỏi bên cho vay "
                        + "về giấy phép và mã số doanh nghiệp. Chưa tìm thấy không có nghĩa là chắc chắn vi phạm, "
                        + "và lãi suất thấp cũng không có nghĩa là an toàn.");
    }
}
