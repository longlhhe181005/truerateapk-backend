package vn.truerate;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vn.truerate.agent.OcrAgent;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OcrAgentTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String fake(String path, String json, AtomicReference<String> body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, ex -> {
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};

    @Test
    void detectsRealImageTypesOnly() {
        assertEquals("image/png", OcrAgent.detectMime(PNG));
        assertEquals("image/jpeg", OcrAgent.detectMime(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0}));
        assertNull(OcrAgent.detectMime("<html>not an image</html>".getBytes()));
        assertNull(OcrAgent.detectMime(new byte[3]));
    }

    @Test
    void openAiVisionRequestAndParsing() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String content = "{\\\"principal_vnd\\\":5000000,\\\"total_repay_vnd\\\":6150000,\\\"term_days\\\":28,\\\"lender_name\\\":\\\"Công ty ABC\\\",\\\"notes\\\":\\\"\\\"}";
        String url = fake("/v1/chat/completions", "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}", body);
        OcrAgent ocr = new OcrAgent("", "m", "http://x", "sk-proj-t", "gpt-4o-mini", url, 5);
        OcrAgent.OcrResult r = ocr.extract(PNG, "image/png");
        assertEquals(5_000_000L, r.principalVnd());
        assertEquals(6_150_000L, r.totalRepayVnd());
        assertEquals(28, r.termDays());
        assertEquals("Công ty ABC", r.lenderName());
        assertTrue(body.get().contains("data:image/png;base64,"));
        assertTrue(body.get().contains("json_schema"));
    }

    @Test
    void readsRateClauseInstalmentsAndFees() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String content = "{\\\"principal_vnd\\\":4000000,\\\"total_repay_vnd\\\":4533041,\\\"term_days\\\":30,\\\"lender_name\\\":\\\"Ngân hàng X\\\","
                + "\\\"stated_rate_percent\\\":60,\\\"interest_clause\\\":\\\"số dư thực tế x số ngày x lãi suất / 365\\\","
                + "\\\"installments\\\":null,\\\"installment_vnd\\\":null,"
                + "\\\"fees\\\":[{\\\"name\\\":\\\"Bảo hiểm khoản vay\\\",\\\"amount_vnd\\\":320000},{\\\"name\\\":\\\"0901234567\\\",\\\"amount_vnd\\\":1}],\\\"notes\\\":\\\"\\\"}";
        String url = fake("/v1/chat/completions", "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}", body);
        OcrAgent.OcrResult r = new OcrAgent("", "m", "http://x", "k", "gpt-4o-mini", url, 5).extract(PNG, "image/png");
        assertEquals(60.0, r.statedRatePercent());
        assertTrue(r.interestClause().contains("365"));
        assertNull(r.installments());
        // the fee whose name looks like a phone number is dropped
        assertEquals(1, r.fees().size());
        assertEquals(320_000L, r.fees().get(0).amountVnd());
        assertTrue(body.get().contains("stated_rate_percent"));
    }

    @Test
    void severalPagesGoInOneCall() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String content = "{\\\"principal_vnd\\\":5000000,\\\"total_repay_vnd\\\":6150000,\\\"term_days\\\":28,\\\"lender_name\\\":\\\"Công ty ABC\\\",\\\"notes\\\":\\\"\\\"}";
        String url = fake("/v1/chat/completions", "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}", body);
        OcrAgent ocr = new OcrAgent("", "m", "http://x", "k", "gpt-4o-mini", url, 5);
        ocr.extract(java.util.List.of(PNG, PNG, PNG), java.util.List.of("image/png", "image/png", "image/png"));
        assertEquals(3, body.get().split("data:image/png;base64,", -1).length - 1);
        assertTrue(body.get().contains("Trang 3/3"));
    }

    @Test
    void anthropicVisionUsesImageBlockAndForcedTool() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String url = fake("/v1/messages", "{\"content\":[{\"type\":\"tool_use\",\"name\":\"extract_loan_fields\",\"input\":{\"principal_vnd\":1000000,\"total_repay_vnd\":null,\"term_days\":null,\"lender_name\":null,\"notes\":\"Ảnh mờ\"}}]}", body);
        OcrAgent.OcrResult r = new OcrAgent("sk-ant-t", "claude-haiku-4-5", url, "", "m", "http://x", 5).extract(PNG, "image/png");
        assertEquals(1_000_000L, r.principalVnd());
        assertNull(r.totalRepayVnd());
        assertTrue(body.get().contains("\"type\":\"image\""));
        assertTrue(body.get().contains("tool_choice"));
    }

    @Test
    void impossibleOrPersonalValuesAreDropped() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String content = "{\\\"principal_vnd\\\":-5,\\\"total_repay_vnd\\\":999999999999999,\\\"term_days\\\":99999,\\\"lender_name\\\":\\\"Nguyen Van A 079123456789\\\",\\\"notes\\\":\\\"ok\\\"}";
        String url = fake("/v1/chat/completions", "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}", body);
        OcrAgent.OcrResult r = new OcrAgent("", "m", "http://x", "k", "m", url, 5).extract(PNG, "image/png");
        assertNull(r.principalVnd());
        assertNull(r.totalRepayVnd());
        assertNull(r.termDays());
        assertNull(r.lenderName());
        assertTrue(r.isEmpty());
    }

    @Test
    void unavailableWithoutKeys() {
        assertFalse(new OcrAgent("", "m", "http://x", "", "m", "http://x", 5).isAvailable());
    }
}
