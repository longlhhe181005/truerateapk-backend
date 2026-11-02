package vn.truerate.core;

import java.util.ArrayList;
import java.util.List;

/** US-02: block inconsistent numbers and say which field to fix. */
public final class InputValidator {

    public static final int MAX_LENDER_NAME = 100;
    public static final long MAX_AMOUNT = 100_000_000_000L;
    public static final int MAX_DAYS = 9200;
    public static final int MAX_MONTHS = 300;

    public record FieldError(String field, String message) {
    }

    private InputValidator() {
    }

    public static List<FieldError> validate(LoanInput in) {
        List<FieldError> errors = new ArrayList<>();
        if (in == null) {
            errors.add(new FieldError("form", "Thiếu dữ liệu."));
            return errors;
        }
        Long p = in.principalVnd();
        Long r = in.totalRepayVnd();
        Integer d = in.termDays();
        String name = in.lenderName() == null ? "" : in.lenderName().trim();

        if (p == null || p <= 0) {
            errors.add(new FieldError("principalVnd", "Nhập số tiền bạn thực nhận (lớn hơn 0)."));
        } else if (p > MAX_AMOUNT) {
            errors.add(new FieldError("principalVnd", "Số tiền thực nhận quá lớn, hãy kiểm tra lại."));
        }
        if (r == null || r <= 0) {
            errors.add(new FieldError("totalRepayVnd", "Nhập tổng số tiền bạn phải trả (lớn hơn 0)."));
        } else if (r > MAX_AMOUNT) {
            errors.add(new FieldError("totalRepayVnd", "Tổng tiền phải trả quá lớn, hãy kiểm tra lại."));
        }
        if (p != null && r != null && p > 0 && r > 0 && r <= p) {
            errors.add(new FieldError("totalRepayVnd",
                    "Tổng tiền phải trả phải lớn hơn số tiền bạn thực nhận. Hãy kiểm tra xem bạn có nhập nhầm hai ô không."));
        }
        if (d == null || d < 1) {
            errors.add(new FieldError("termDays", "Nhập số ngày vay (ít nhất 1 ngày)."));
        } else if (d > MAX_DAYS) {
            errors.add(new FieldError("termDays", "Số ngày vay tối đa là " + MAX_DAYS + " ngày."));
        }
        if (name.isEmpty()) {
            errors.add(new FieldError("lenderName", "Nhập tên bên cho vay như ghi trên hợp đồng hoặc ứng dụng."));
        } else if (name.length() > MAX_LENDER_NAME) {
            errors.add(new FieldError("lenderName", "Tên bên cho vay tối đa " + MAX_LENDER_NAME + " ký tự."));
        }
        return errors;
    }
}
