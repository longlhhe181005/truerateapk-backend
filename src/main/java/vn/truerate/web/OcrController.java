package vn.truerate.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.truerate.agent.OcrAgent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads four fields from a photo. The image lives in memory for this request only and is never written to disk or logged. */
@RestController
public class OcrController {

    private static final Logger log = LoggerFactory.getLogger(OcrController.class);
    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final int MAX_FILES = 6;

    private final OcrAgent ocr;

    public OcrController(OcrAgent ocr) {
        this.ocr = ocr;
    }

    @PostMapping(value = "/api/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> read(@RequestParam("file") List<MultipartFile> files,
                                                     @RequestParam(value = "consent", defaultValue = "false") boolean consent) {
        if (!consent) {
            return err(HttpStatus.BAD_REQUEST, "Bạn cần đồng ý gửi ảnh tới dịch vụ AI để đọc 4 mục trước khi tải lên.");
        }
        if (!ocr.isAvailable()) {
            return err(HttpStatus.SERVICE_UNAVAILABLE, "Chức năng đọc ảnh cần cấu hình API key. Bạn vẫn có thể nhập tay 4 ô.");
        }
        if (files == null || files.isEmpty()) {
            return err(HttpStatus.BAD_REQUEST, "Hãy chọn ít nhất một ảnh.");
        }
        if (files.size() > MAX_FILES) {
            return err(HttpStatus.BAD_REQUEST, "Tối đa " + MAX_FILES + " ảnh mỗi lần.");
        }
        try {
            List<byte[]> images = new java.util.ArrayList<>();
            List<String> mimes = new java.util.ArrayList<>();
            for (MultipartFile file : files) {
                if (file.isEmpty() || file.getSize() > MAX_BYTES) {
                    return err(HttpStatus.PAYLOAD_TOO_LARGE, "Có ảnh trống hoặc lớn hơn 5 MB. Hãy chụp lại hoặc thu nhỏ ảnh.");
                }
                byte[] bytes = file.getBytes();
                String mime = OcrAgent.detectMime(bytes);
                if (mime == null) {
                    return err(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Chỉ hỗ trợ ảnh JPG, PNG hoặc WebP.");
                }
                images.add(bytes);
                mimes.add(mime);
            }
            OcrAgent.OcrResult r = ocr.extract(images, mimes);
            if (r.isEmpty()) {
                return err(HttpStatus.UNPROCESSABLE_ENTITY, "Không đọc được mục nào từ ảnh. Hãy chụp rõ hơn hoặc nhập tay.");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("principalVnd", r.principalVnd());
            out.put("totalRepayVnd", r.totalRepayVnd());
            out.put("termDays", r.termDays());
            out.put("lenderName", r.lenderName());
            out.put("statedRatePercent", r.statedRatePercent());
            out.put("interestClause", r.interestClause());
            out.put("installments", r.installments());
            out.put("installmentVnd", r.installmentVnd());
            out.put("fees", r.fees());
            out.put("notes", r.notes());
            out.put("provider", r.provider());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.warn("OCR failed: {}", e.toString());
            return err(HttpStatus.BAD_GATEWAY, "Không đọc được ảnh lúc này. Bạn có thể nhập tay 4 ô.");
        }
    }

    private static ResponseEntity<Map<String, Object>> err(HttpStatus s, String msg) {
        return ResponseEntity.status(s).body(Map.of("errors", List.of(Map.of("field", "ocr", "message", msg))));
    }
}
