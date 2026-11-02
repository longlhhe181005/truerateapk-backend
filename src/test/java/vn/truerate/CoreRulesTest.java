package vn.truerate;

import org.junit.jupiter.api.Test;
import vn.truerate.agent.AgentContext;
import vn.truerate.agent.KitAgent;
import vn.truerate.agent.NextStep;
import vn.truerate.agent.OutputValidator;
import vn.truerate.core.AprCalculator;
import vn.truerate.core.InputValidator;
import vn.truerate.core.LoanInput;
import vn.truerate.core.ScenarioCalculator;
import vn.truerate.license.LicenseRegistry;
import vn.truerate.license.LicenseRegistry.Status;
import vn.truerate.risk.RiskClassifier;
import vn.truerate.risk.RiskClassifier.Code;

import static org.junit.jupiter.api.Assertions.*;

class CoreRulesTest {

    // ---- APR (TC-01 and others) ----
    @Test
    void tc01_exampleFromProject() {
        double apr = AprCalculator.aprPercent(5_000_000, 6_150_000, 28);
        assertEquals(1386.0, apr, 1.5);
    }

    @Test
    void simpleAnnualRateMatchesHandCalculation() {
        // 533,041 / 4,000,000 = 13.326% per 30 days; x 365/30 = 162.13% per year
        assertEquals(162.13, AprCalculator.simplePercent(4_000_000, 4_533_041, 30), 0.01);
        // 230,000 more on 1,000,000 over 365 days is exactly 23% per year
        assertEquals(23.0, AprCalculator.simplePercent(1_000_000, 1_230_000, 365), 0.0001);
        // the compound figure for the same loan is much larger
        assertEquals(358.15, AprCalculator.aprPercent(4_000_000, 4_533_041, 30), 0.05);
    }

    @Test
    void validatorRejectsAmountsThatCodeDidNotCompute() {
        var ctx = new AgentContext(13.3, 65_753, 328_767, "LICENSED_VERY_HIGH", "high", 4_000_000, 4_533_041, 30, "X", "LICENSED");
        // wrong extra cost (real value is 533.041)
        assertNotNull(OutputValidator.check("Bạn phải trả thêm 1.330.041 VND.", "So với mức 20%.", NextStep.SEEK_LICENSED_LENDER, ctx));
        // correct amounts, the references for this term, a percentage and a year are fine
        assertNull(OutputValidator.check("Bạn nhận 4.000.000 VND, trả 4.533.041 VND, tức thêm 533.041 VND, khoảng 13,3% trong 30 ngày.",
                "Cao hơn mức 65.753 VND và 328.767 VND, là mốc 20% và 100% trong Bộ luật Dân sự 2015 tính cho 30 ngày.", NextStep.SEEK_LICENSED_LENDER, ctx));
    }

    @Test
    void fallbackTextNeverAnnualisesTheRate() {
        var ctx = new AgentContext(23.0, 76_712, 383_562, "SERIOUS", "serious", 5_000_000, 6_150_000, 28, "X", "NOT_FOUND");
        var reply = vn.truerate.agent.FallbackExplainer.explain(ctx, "");
        assertTrue(reply.explanation().contains("23,0% số tiền nhận được trong 28 ngày"));
        assertFalse(reply.explanation().contains("/năm"));
        assertTrue(reply.legalComparison().contains("76.712đ") && reply.legalComparison().contains("383.562đ"));
    }

    @Test
    void instalmentRateMatchesBankCalculator() {
        // BIDV calculator: 124,500,000 at 7%/year, 123 equal monthly payments of 1,421,202 = 174,807,862 in total
        assertEquals(7.0, AprCalculator.instalmentSimplePercent(124_500_000, 174_807_862, 123), 0.01);
        // the one-payment formula would wrongly give about 3.9% for the same totals
        assertEquals(3.94, AprCalculator.simplePercent(124_500_000, 174_807_862, 3741), 0.01);
    }

    @Test
    void instalmentFeeTakenUpFrontRaisesTheRate() {
        // same payments, but a 5,000,000 fee is deducted so only 119,500,000 is received
        assertTrue(AprCalculator.instalmentSimplePercent(119_500_000, 174_807_862, 123) > 7.7);
    }

    @Test
    void scenarioFlagsFeeAddedToPrincipalAsHiddenCost() {
        // VPBank-style loan: received 4,000,000; repay 4,533,041 in 30 days; stated 60%/year
        var list = ScenarioCalculator.oneTime(4_000_000, 4_533_041, 30, 60.0, 0);
        var simple = list.get(0);
        assertEquals(4_320_000L, simple.impliedBasisVnd(), 5);
        assertEquals(ScenarioCalculator.Status.HIDDEN, simple.status());
        assertEquals("SIMPLE", ScenarioCalculator.best(list).code());
        // once the 320,000 insurance fee is listed, nothing is unexplained
        var withFee = ScenarioCalculator.oneTime(4_000_000, 4_533_041, 30, 60.0, 320_000);
        assertEquals(ScenarioCalculator.Status.FEES_EXPLAIN, withFee.get(0).status());
    }

    @Test
    void scenarioMatchesBankInstalments() {
        // BIDV example: 124,500,000 at 7%/year, 123 monthly payments
        var list = ScenarioCalculator.monthly(124_500_000, 174_807_846, 123, 7.0, 0);
        assertEquals(ScenarioCalculator.Status.MATCH, list.get(0).status());
        assertEquals("DECLINING", ScenarioCalculator.best(list).code());
        assertNotEquals(ScenarioCalculator.Status.MATCH, list.get(1).status());
    }

    @Test
    void lowRateDoesNotHideWarningSignsForUnverifiedLender() {
        var c = new RiskClassifier();
        assertEquals(Code.NOT_FOUND, c.classify(Status.NOT_FOUND, 25.0, 0).code());
        var withSign = c.classify(Status.NOT_FOUND, 25.0, 1);
        assertEquals(Code.RISK_SIGNALS, withSign.code());
        assertEquals("high", withSign.level());
        assertEquals(Code.LICENSED_HIGH, c.classify(Status.LICENSED, 25.0, 2).code());
        assertEquals(Code.SERIOUS, c.classify(Status.NOT_FOUND, 150.0, 1).code());
    }

    @Test
    void commonBankNamesAreFoundInDemoList() throws Exception {
        var reg = new LicenseRegistry();
        for (String n : new String[]{"MB", "mb bank", "MBBank", "Ngân hàng TMCP Quân đội", "Ngân hàng Quân đội", "Vietcombank", "BIDV", "Techcombank"}) {
            assertEquals(Status.LICENSED, reg.check(n).status(), n);
        }
        // an unknown or look-alike name is never shown as licensed
        assertNotEquals(Status.LICENSED, reg.check("MB Vay Nhanh 247").status());
        assertNotEquals(Status.LICENSED, reg.check("Ngân hàng Vay Nhanh").status());
    }

    @Test
    void referenceCostForTheLoanPeriod() {
        // 20% per year on 4,000,000 for 30 days = 65,753; the borrower pays 533,041 extra, about 8.1 times as much
        long cap = AprCalculator.referenceCostOneTime(4_000_000, 30, 20.0);
        assertEquals(65_753L, cap);
        assertEquals(8.1, 533_041.0 / cap, 0.05);
        // same ratio as 162.1% against 20%
        assertEquals(162.13 / 20.0, 533_041.0 / cap, 0.05);
        // instalments: 12 equal monthly payments at 20% per year on 10,000,000 cost 1,116,141 in total interest
        long inst = AprCalculator.referenceCostInstalments(10_000_000, 12, 20.0);
        assertEquals(1_116_141L, inst, 2);
    }

    @Test
    void kitTextIsCheckedBeforeTheUserSeesIt() {
        var in = new KitAgent.KitInput("Vay Nhanh 247", 4_000_000, 4_533_041, 30, null, null, 60.0, 6_480_000L, 320_000L,
                java.util.List.of(), "SERIOUS", "NOT_FOUND", java.util.List.of());
        var steps = java.util.List.of("Giữ lại ảnh hợp đồng và sao kê.", "Hỏi riêng khoản 320.000đ bằng văn bản.", "Kiểm tra mã số doanh nghiệp.");
        String good = "Kính gửi Vay Nhanh 247, tôi thực nhận 4.000.000đ và phải trả 4.533.041đ. Đề nghị cung cấp chi tiết các khoản phí. [Họ tên] [Ngày]";
        assertNull(KitAgent.check(good, steps, in));
        // an amount that is not in the user's own result
        assertNotNull(KitAgent.check(good.replace("4.533.041đ", "4.900.000đ"), steps, in));
        // accusation wording is not allowed
        assertNotNull(KitAgent.check(good + " Đây là lừa đảo.", steps, in));
        // a phone number must not appear
        assertNotNull(KitAgent.check(good + " Liên hệ 0901234567.", steps, in));
        // too few steps
        assertNotNull(KitAgent.check(good, java.util.List.of("Một bước"), in));
    }

    @Test
    void oneYearSimpleCase() {
        assertEquals(20.0, AprCalculator.aprPercent(1_000_000, 1_200_000, 365), 0.001);
    }

    @Test
    void safeExampleFromDemo() {
        assertEquals(14.0, AprCalculator.aprPercent(5_000_000, 5_050_000, 28) / 1.0, 1.0);
    }

    @Test
    void extremeValuesAreCapped() {
        double apr = AprCalculator.aprPercent(1, 1_000_000_000L, 1);
        assertTrue(AprCalculator.isCapped(apr));
    }

    // ---- validation (US-02) ----
    @Test
    void rejectsRepaymentNotLargerThanPrincipal() {
        var errors = InputValidator.validate(new LoanInput(5_000_000L, 5_000_000L, 30, "X"));
        assertTrue(errors.stream().anyMatch(e -> e.field().equals("totalRepayVnd")));
    }

    @Test
    void rejectsBadTermAndLongName() {
        assertFalse(InputValidator.validate(new LoanInput(1L, 2L, 0, "X")).isEmpty());
        assertFalse(InputValidator.validate(new LoanInput(1L, 2L, 10, "x".repeat(101))).isEmpty());
        assertTrue(InputValidator.validate(new LoanInput(1_000_000L, 1_100_000L, 30, "FE Credit")).isEmpty());
    }

    // ---- labelling matrix (BR-02): six cases ----
    private final RiskClassifier classifier = new RiskClassifier();

    @Test
    void licensedLowAmberAndVeryHigh() {
        assertEquals(Code.LICENSED_LOW, classifier.classify(Status.LICENSED, 15).code());
        assertEquals(Code.LICENSED_HIGH, classifier.classify(Status.LICENSED, 40).code());
        assertEquals(Code.LICENSED_VERY_HIGH, classifier.classify(Status.LICENSED, 150).code());
    }

    @Test
    void notFoundAndSerious() {
        assertEquals(Code.NOT_FOUND, classifier.classify(Status.NOT_FOUND, 40).code());
        assertEquals(Code.NOT_FOUND, classifier.classify(Status.NOT_FOUND, 100).code());
        assertEquals(Code.SERIOUS, classifier.classify(Status.NOT_FOUND, 150).code());
    }

    @Test
    void undeterminedWhateverTheRate() {
        assertEquals(Code.UNDETERMINED, classifier.classify(Status.UNDETERMINED, 5).code());
        assertEquals(Code.UNDETERMINED, classifier.classify(Status.UNDETERMINED, 500).code());
    }

    @Test
    void highRateAloneIsNeverSerious() {
        assertNotEquals(Code.SERIOUS, classifier.classify(Status.LICENSED, 5000).code());
    }

    // ---- licence matching ----
    private final LicenseRegistry registry = newRegistry();

    private static LicenseRegistry newRegistry() {
        try {
            return new LicenseRegistry();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void exactNameIsLicensedEvenWithDiacriticsAndCase() {
        assertEquals(Status.LICENSED, registry.check("  fe CREDIT ").status());
        assertEquals(Status.LICENSED, registry.check("Công ty Tài chính Home Credit").status());
    }

    @Test
    void copycatNameIsNeverLicensed() {
        assertEquals(Status.UNDETERMINED, registry.check("FE Credit Vay Nhanh 247").status());
        assertEquals(Status.UNDETERMINED, registry.check("Fe Crdit").status());
    }

    @Test
    void unknownNameIsNotFound() {
        assertEquals(Status.NOT_FOUND, registry.check("Vay Nhanh 247").status());
    }

    // ---- output validation (last defence) ----
    private static AgentContext ctx(String code, String level) {
        return new AgentContext(20.0, 76_712, 383_562, code, level, 5_000_000, 6_000_000, 28, "X", "NOT_FOUND");
    }

    @Test
    void validatorBlocksBannedWordsExceptWhenSerious() {
        assertNotNull(OutputValidator.check("Đây là tín dụng đen.", "Vượt mốc.", NextStep.SEEK_LEGAL_ADVICE, ctx("NOT_FOUND", "caution")));
        assertNull(OutputValidator.check("Có dấu hiệu tín dụng đen.", "Vượt mốc.", NextStep.SEEK_LEGAL_ADVICE, ctx("SERIOUS", "serious")));
        assertNotNull(OutputValidator.check("Chắc chắn phạm tội.", "Vượt mốc.", NextStep.SEEK_LEGAL_ADVICE, ctx("SERIOUS", "serious")));
    }

    @Test
    void validatorBlocksNoActionOnWorryingResult() {
        assertNotNull(OutputValidator.check("Ổn.", "Ổn.", NextStep.NO_ACTION_NEEDED, ctx("SERIOUS", "serious")));
        assertNull(OutputValidator.check("Ổn.", "Ổn.", NextStep.NO_ACTION_NEEDED, ctx("LICENSED_LOW", "low")));
    }

    @Test
    void validatorBlocksEmptyOrInvalid() {
        assertNotNull(OutputValidator.check("", "x", NextStep.SEEK_LEGAL_ADVICE, ctx("NOT_FOUND", "caution")));
        assertNotNull(OutputValidator.check("x", "x", null, ctx("NOT_FOUND", "caution")));
    }
}
