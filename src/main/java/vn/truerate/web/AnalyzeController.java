package vn.truerate.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.truerate.agent.AgentContext;
import vn.truerate.agent.AgentReply;
import vn.truerate.agent.ExplainerAgent;
import vn.truerate.agent.KitAgent;
import vn.truerate.core.AprCalculator;
import vn.truerate.core.InputValidator;
import vn.truerate.core.LoanInput;
import vn.truerate.core.ScenarioCalculator;
import vn.truerate.license.LicenseRegistry;
import vn.truerate.risk.RightsGuide;
import vn.truerate.risk.RiskClassifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api")
public class AnalyzeController {

    public record AnalyzeRequest(Long principalVnd, Long totalRepayVnd, Integer termDays, String lenderName, Boolean consent,
                                 Long contractAmountVnd, String repaymentType, Integer installments, Double statedRatePercent,
                                 List<FeeLine> fees, Long monthlyIncomeVnd, List<String> signals) {
    }

    public record IntentRequest(String choice) {
    }

    public record KitRequest(String lenderName, Long principalVnd, Long totalRepayVnd, Integer termDays, Integer installments,
                             Long installmentVnd, Double statedRatePercent, Long contractAmountVnd, Long hiddenCostVnd,
                             List<FeeLine> fees, String riskCode, String licenceStatus, List<String> signals) {
    }

    private static final Map<String, String> SIGNAL_TEXT = Map.of(
            "FEE_BEFORE", "Bị yêu cầu trả phí trước khi nhận tiền (phí hồ sơ, phí xác minh, bảo hiểm...)",
            "CONTACTS", "Ứng dụng xin quyền truy cập danh bạ, ảnh hoặc vị trí",
            "NAME_MISMATCH", "Tên pháp lý trong hợp đồng khác tên ứng dụng, hoặc không tra được mã số doanh nghiệp",
            "HARASSMENT", "Đã bị nhắn tin hoặc gọi điện đe dọa, làm phiền người thân");

    private static final java.util.Set<String> INTENTS = java.util.Set.of("STILL_BORROW", "BORROW_LESS", "CHOOSE_OTHER", "ASK_LENDER", "UNSURE");
    private final Map<String, AtomicLong> intents = new java.util.concurrent.ConcurrentHashMap<>();

    public record FeeLine(String name, Long amountVnd) {
    }

    public record FeedbackRequest(Boolean helpful) {
    }

    private final LicenseRegistry registry;
    private final RiskClassifier classifier;
    private final RightsGuide rights;
    private final ExplainerAgent agent;
    private final AtomicLong checks = new AtomicLong();
    private final AtomicLong helpfulYes = new AtomicLong();
    private final AtomicLong helpfulNo = new AtomicLong();

    private final KitAgent kitAgent;

    public AnalyzeController(LicenseRegistry registry, RiskClassifier classifier, RightsGuide rights, ExplainerAgent agent, KitAgent kitAgent) {
        this.kitAgent = kitAgent;
        this.registry = registry;
        this.classifier = classifier;
        this.rights = rights;
        this.agent = agent;
    }

    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody AnalyzeRequest req) {
        if (req == null || !Boolean.TRUE.equals(req.consent())) {
            return error(HttpStatus.BAD_REQUEST, List.of(new InputValidator.FieldError("consent",
                    "Bạn cần đồng ý với thông báo sử dụng dữ liệu trước khi tiếp tục.")));
        }
        boolean monthly = "MONTHLY".equals(req.repaymentType());
        Integer n = req.installments();
        if (monthly && (n == null || n < 2 || n > InputValidator.MAX_MONTHS)) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, List.of(new InputValidator.FieldError("installments",
                    "Nhập số kỳ trả góp (từ 2 đến " + InputValidator.MAX_MONTHS + " tháng).")));
        }
        Double stated = req.statedRatePercent();
        if (stated != null && (stated <= 0 || stated > 1000)) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, List.of(new InputValidator.FieldError("statedRatePercent",
                    "Lãi suất hợp đồng ghi không hợp lệ. Hãy để trống nếu không biết.")));
        }
        List<FeeLine> fees = new java.util.ArrayList<>();
        if (req.fees() != null) {
            for (FeeLine f : req.fees()) {
                if (f == null || f.name() == null || f.name().isBlank() || f.amountVnd() == null || f.amountVnd() <= 0
                        || f.amountVnd() > InputValidator.MAX_AMOUNT || fees.size() >= 8) {
                    continue;
                }
                String nm = f.name().replaceAll("\\p{Cntrl}", " ").trim();
                fees.add(new FeeLine(nm.length() > 60 ? nm.substring(0, 60) : nm, f.amountVnd()));
            }
        }
        long listedFees = fees.stream().mapToLong(FeeLine::amountVnd).sum();
        Integer days = monthly ? (int) Math.round(n * 365.0 / 12) : req.termDays();
        LoanInput in = new LoanInput(req.principalVnd(), req.totalRepayVnd(), days, req.lenderName());
        List<InputValidator.FieldError> errors = InputValidator.validate(in);
        if (!errors.isEmpty()) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, errors);
        }

        Long income = req.monthlyIncomeVnd();
        if (income != null && (income <= 0 || income > 10_000_000_000L)) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, List.of(new InputValidator.FieldError("monthlyIncomeVnd",
                    "Thu nhập không hợp lệ. Hãy để trống nếu bạn không muốn nhập.")));
        }
        List<String> signalCodes = new java.util.ArrayList<>();
        if (req.signals() != null) {
            for (String sg : req.signals()) {
                if (sg != null && SIGNAL_TEXT.containsKey(sg) && !signalCodes.contains(sg)) {
                    signalCodes.add(sg);
                }
            }
        }
        Long contract = req.contractAmountVnd();
        if (contract != null && (contract <= 0 || contract > 100_000_000_000L)) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, List.of(new InputValidator.FieldError("contractAmountVnd",
                    "Số tiền ghi trong hợp đồng không hợp lệ. Hãy để trống nếu bạn không muốn nhập.")));
        }

        String lender = in.lenderName().trim();
        double apr = monthly
                ? AprCalculator.instalmentSimplePercent(in.principalVnd(), in.totalRepayVnd(), n)
                : AprCalculator.simplePercent(in.principalVnd(), in.totalRepayVnd(), in.termDays());
        double compound = monthly
                ? AprCalculator.instalmentCompoundPercent(in.principalVnd(), in.totalRepayVnd(), n)
                : AprCalculator.aprPercent(in.principalVnd(), in.totalRepayVnd(), in.termDays());
        boolean capped = AprCalculator.isCapped(apr);
        LicenseRegistry.Match licence = registry.check(lender);
        RiskClassifier.Label label = classifier.classify(licence.status(), apr, signalCodes.size());

        long extra = in.totalRepayVnd() - in.principalVnd();
        long cap20 = monthly ? AprCalculator.referenceCostInstalments(in.principalVnd(), n, RiskClassifier.CIVIL_REFERENCE_PERCENT)
                : AprCalculator.referenceCostOneTime(in.principalVnd(), in.termDays(), RiskClassifier.CIVIL_REFERENCE_PERCENT);
        long cap100 = monthly ? AprCalculator.referenceCostInstalments(in.principalVnd(), n, RiskClassifier.CRIMINAL_REFERENCE_PERCENT)
                : AprCalculator.referenceCostOneTime(in.principalVnd(), in.termDays(), RiskClassifier.CRIMINAL_REFERENCE_PERCENT);
        double termPercent = (double) extra / in.principalVnd() * 100.0;

        AgentReply reply = agent.explain(new AgentContext(termPercent, cap20, cap100, label.code().name(), label.level(),
                in.principalVnd(), in.totalRepayVnd(), in.termDays(), lender, licence.status().name()));
        checks.incrementAndGet();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aprPercent", Math.round(apr * 10.0) / 10.0);
        out.put("compoundPercent", Math.round(compound * 10.0) / 10.0);
        out.put("compoundCapped", AprCalculator.isCapped(compound));
        out.put("repaymentType", monthly ? "MONTHLY" : "ONE_TIME");
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("cap20Vnd", cap20);
        cap.put("cap100Vnd", cap100);
        cap.put("actualVnd", extra);
        cap.put("capPercentOfPrincipal", Math.round((double) cap20 / in.principalVnd() * 1000.0) / 10.0);
        cap.put("multipleOf20", cap20 > 0 ? Math.round((double) extra / cap20 * 10.0) / 10.0 : null);
        out.put("legalCap", cap);
        if (stated != null) {
            List<ScenarioCalculator.Scenario> scen = monthly
                    ? ScenarioCalculator.monthly(in.principalVnd(), in.totalRepayVnd(), n, stated, listedFees)
                    : ScenarioCalculator.oneTime(in.principalVnd(), in.totalRepayVnd(), in.termDays(), stated, listedFees);
            ScenarioCalculator.Scenario best = ScenarioCalculator.best(scen);
            List<Map<String, Object>> rowsOut = new java.util.ArrayList<>();
            for (ScenarioCalculator.Scenario sc : scen) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", sc.code());
                m.put("title", sc.title());
                m.put("how", sc.how());
                m.put("impliedBasisVnd", sc.impliedBasisVnd());
                m.put("gapVnd", sc.gapVnd());
                m.put("unexplainedVnd", sc.unexplainedVnd());
                m.put("status", sc.status().name());
                m.put("best", sc == best);
                rowsOut.add(m);
            }
            out.put("scenarios", rowsOut);
            out.put("bestScenario", best.code());
            if (best.status() == ScenarioCalculator.Status.HIDDEN) {
                out.put("hiddenCostVnd", best.unexplainedVnd());
            }
        }
        if (!signalCodes.isEmpty()) {
            out.put("signals", signalCodes.stream().map(SIGNAL_TEXT::get).toList());
        }
        if (income != null) {
            long payment = monthly ? Math.round((double) in.totalRepayVnd() / n) : in.totalRepayVnd();
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("incomeVnd", income);
            b.put("paymentVnd", payment);
            b.put("percent", Math.round((double) payment / income * 1000.0) / 10.0);
            b.put("basis", monthly ? "MONTHLY" : "ONE_TIME");
            out.put("burden", b);
        }
        if (!fees.isEmpty()) {
            out.put("fees", fees);
            out.put("listedFeesVnd", listedFees);
        }
        if (stated != null) {
            out.put("statedRatePercent", stated);
        }
        if (monthly) {
            double payment = (double) in.totalRepayVnd() / n;
            double i = AprCalculator.monthlyRate(in.principalVnd(), payment, n);
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            double balance = in.principalVnd();
            for (int k = 1; k <= n; k++) {
                double interest = balance * i;
                double principalPart = payment - interest;
                balance = Math.max(0, balance - principalPart);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("k", k);
                row.put("payment", Math.round(payment));
                row.put("interest", Math.round(interest));
                row.put("principal", Math.round(principalPart));
                row.put("balance", Math.round(balance));
                rows.add(row);
            }
            out.put("installments", n);
            out.put("installmentVnd", Math.round(payment));
            out.put("schedule", rows);
        }
        out.put("aprCapped", capped);
        out.put("principalVnd", in.principalVnd());
        out.put("totalRepayVnd", in.totalRepayVnd());
        out.put("extraCostVnd", extra);
        out.put("extraPercent", Math.round((double) extra / in.principalVnd() * 1000.0) / 10.0);
        out.put("termDays", in.termDays());
        out.put("lenderName", lender);
        out.put("licence", Map.of(
                "status", licence.status().name(),
                "matchedName", licence.matchedName() == null ? "" : licence.matchedName(),
                "listVersion", licence.listVersion(),
                "listSource", licence.listSource()));
        out.put("risk", Map.of("code", label.code().name(), "level", label.level(), "title", label.title(), "message", label.message()));
        if (contract != null && contract > in.principalVnd()) {
            long gap = contract - in.principalVnd();
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("contractAmountVnd", contract);
            g.put("shortfallVnd", gap);
            g.put("shortfallPercent", Math.round((double) gap / contract * 1000.0) / 10.0);
            g.put("message", "Hợp đồng ghi " + contract + " đồng nhưng bạn chỉ nhận " + in.principalVnd()
                    + " đồng. Phần chênh có thể là phí bảo hiểm hoặc phí khác trừ vào khoản vay, hoặc khoản vay bị giảm sau khi bạn đồng ý. "
                    + "Hãy hỏi bên cho vay bằng văn bản để họ giải thích rõ từng khoản bị trừ, và giữ lại ảnh hợp đồng, màn hình giải ngân và sao kê.");
            out.put("contractGap", g);
        }
        out.put("explanation", reply.explanation());
        out.put("legalComparison", reply.legalComparison());
        out.put("nextStep", reply.nextStep().name());
        out.put("nextStepText", reply.nextStep().textVi());
        out.put("rights", rights.forLevel(label.level()));
        out.put("ai", Map.of("mode", reply.mode(), "note", reply.note() == null ? "" : reply.note()));
        out.put("disclaimer", RightsGuide.DISCLAIMER);
        return ResponseEntity.ok(out);
    }

    /** AI writes the "I already took this loan" steps and the letter for one loan. The page keeps its fixed template when this is offline. */
    @PostMapping("/kit")
    public ResponseEntity<Map<String, Object>> kit(@RequestBody KitRequest r) {
        if (r == null || r.principalVnd() == null || r.totalRepayVnd() == null || r.lenderName() == null || r.lenderName().isBlank()
                || r.principalVnd() <= 0 || r.totalRepayVnd() <= r.principalVnd()
                || r.totalRepayVnd() > InputValidator.MAX_AMOUNT) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, List.of(new InputValidator.FieldError("form", "Thiếu số liệu khoản vay.")));
        }
        List<KitAgent.FeeItem> fees = new java.util.ArrayList<>();
        if (r.fees() != null) {
            for (FeeLine f : r.fees()) {
                if (f != null && f.name() != null && !f.name().isBlank() && f.amountVnd() != null && f.amountVnd() > 0
                        && f.amountVnd() <= InputValidator.MAX_AMOUNT && fees.size() < 8) {
                    fees.add(new KitAgent.FeeItem(f.name(), f.amountVnd()));
                }
            }
        }
        List<String> signals = new java.util.ArrayList<>();
        if (r.signals() != null) {
            for (String t : r.signals()) {
                if (t != null && SIGNAL_TEXT.containsValue(t) && signals.size() < 4) {
                    signals.add(t);
                }
            }
        }
        KitAgent.Kit k = kitAgent.compose(new KitAgent.KitInput(r.lenderName().trim(), r.principalVnd(), r.totalRepayVnd(), r.termDays(),
                r.installments(), r.installmentVnd(), r.statedRatePercent(), r.contractAmountVnd(), r.hiddenCostVnd(), fees,
                r.riskCode(), r.licenceStatus(), signals));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", k.mode());
        out.put("steps", k.steps());
        out.put("letter", k.letter());
        out.put("note", k.note());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/intent")
    public Map<String, Object> intent(@RequestBody IntentRequest req) {
        if (req != null && req.choice() != null && INTENTS.contains(req.choice())) {
            intents.computeIfAbsent(req.choice(), k -> new AtomicLong()).incrementAndGet();
        }
        return Map.of("ok", true);
    }

    @PostMapping("/feedback")
    public Map<String, Object> feedback(@RequestBody FeedbackRequest req) {
        if (req != null && req.helpful() != null) {
            (req.helpful() ? helpfulYes : helpfulNo).incrementAndGet();
        }
        return Map.of("ok", true);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("aiMode", agent.isOnline() ? "online" : "offline");
        out.put("provider", agent.provider());
        out.put("ocr", agent.isOnline());
        out.put("model", agent.model());
        out.put("checks", checks.get());
        out.put("helpfulYes", helpfulYes.get());
        out.put("helpfulNo", helpfulNo.get());
        Map<String, Long> ints = new LinkedHashMap<>();
        intents.forEach((k, v) -> ints.put(k, v.get()));
        out.put("intents", ints);
        return out;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, List<InputValidator.FieldError> errors) {
        return ResponseEntity.status(status).body(Map.of("errors", errors));
    }
}
