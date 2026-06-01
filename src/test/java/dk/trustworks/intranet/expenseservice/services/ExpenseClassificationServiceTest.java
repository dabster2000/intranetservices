package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.dto.ExpenseClassificationDTOs;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseClassification;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ExpenseClassificationServiceTest {

    @Inject ExpenseClassificationService service;

    @Test
    void resolvesGiftForClientToHiddenAccountMapping() {
        ExpenseClassificationDTOs.ResolveResponse response = service.resolve(
                "missing-user",
                new ExpenseClassificationDTOs.ResolveRequest(
                        "2026-05-19.v1",
                        null,
                        true,
                        false,
                        List.of(
                                new ExpenseClassificationDTOs.Answer("root", "gift", "AI", 0.91, "flower shop", true),
                                new ExpenseClassificationDTOs.Answer("gift_recipient", "client", "USER", null, null, true)
                        ),
                        List.of()
                )
        );

        assertEquals("RESOLVED", response.state());
        assertNotNull(response.result());
        assertEquals("CLIENT_GIFT", response.result().resultKey());
        assertEquals("client_gift", response.result().accountKey());
        assertEquals("4008", response.result().accountNumber());
        assertFalse(response.result().requiresFinanceReview());
    }

    @Test
    void routesTbdHardwareBranchToFinanceFallback() {
        ExpenseClassificationDTOs.ResolveResponse response = service.resolve(
                "missing-user",
                new ExpenseClassificationDTOs.ResolveRequest(
                        "2026-05-19.v1",
                        null,
                        false,
                        false,
                        List.of(
                                new ExpenseClassificationDTOs.Answer("root", "hardware", "USER", null, null, true),
                                new ExpenseClassificationDTOs.Answer("hardware_under_threshold", "no", "USER", null, null, true)
                        ),
                        List.of()
                )
        );

        assertEquals("RESOLVED", response.state());
        assertEquals("HARDWARE_FINANCE_REVIEW", response.result().resultKey());
        assertEquals("finance_review_fallback", response.result().accountKey());
        assertEquals("9998", response.result().accountNumber());
        assertTrue(response.result().requiresFinanceReview());
    }

    @Test
    void discardsInvalidAnswersBeforeResolving() {
        ExpenseClassificationDTOs.ResolveResponse response = service.resolve(
                "missing-user",
                new ExpenseClassificationDTOs.ResolveRequest(
                        "2026-05-19.v1",
                        null,
                        true,
                        false,
                        List.of(new ExpenseClassificationDTOs.Answer("root", "made_up", "AI", 0.99, "bad", true)),
                        List.of()
                )
        );

        assertEquals("NEEDS_ANSWERS", response.state());
        assertTrue(response.acceptedAnswers().isEmpty());
        assertEquals("root", response.unansweredNodes().getFirst().nodeKey());
    }

    @Test
    void normalizesModelProposedAnswerSourceToAi() {
        String raw = """
                {
                  "receiptFacts": {
                    "merchantName": "Bloom",
                    "date": "2026-05-19",
                    "amount": 450.0,
                    "currency": "DKK",
                    "supplierCountry": "DK",
                    "visibleVatText": null,
                    "lineItems": ["Bouquet"],
                    "documentType": "receipt"
                  },
                  "proposedAnswers": [
                    {
                      "nodeKey": "root",
                      "answerKey": "gift",
                      "source": "receipt",
                      "confidence": 0.91,
                      "evidence": "Flower shop receipt",
                      "accepted": true
                    }
                  ],
                  "warnings": [],
                  "rawModelSummary": "Flower shop receipt"
                }
                """;

        ExpenseClassificationDTOs.AnalyzeResponse response = service.parseAnalysis("2026-05-19.v1", raw);

        assertEquals(1, response.proposedAnswers().size());
        assertEquals("AI", response.proposedAnswers().getFirst().source());
    }

    @Test
    void dropsAiProposedOtherUnsureSoEmployeeMustChoose() {
        String raw = """
                {
                  "receiptFacts": {
                    "merchantName": "Some Shop",
                    "date": "2026-05-19",
                    "amount": 200.0,
                    "currency": "DKK",
                    "supplierCountry": "DK",
                    "visibleVatText": null,
                    "lineItems": ["Misc item"],
                    "documentType": "receipt"
                  },
                  "proposedAnswers": [
                    {
                      "nodeKey": "root",
                      "answerKey": "other_unsure",
                      "source": "AI",
                      "confidence": 0.95,
                      "evidence": "Could not determine a category",
                      "accepted": true
                    }
                  ],
                  "warnings": [],
                  "rawModelSummary": "Ambiguous receipt"
                }
                """;

        ExpenseClassificationDTOs.AnalyzeResponse response = service.parseAnalysis("2026-05-19.v1", raw);

        // The AI catch-all must never reach the employee — root stays unanswered so the wizard asks.
        assertTrue(response.proposedAnswers().stream()
                        .noneMatch(answer -> "root".equals(answer.nodeKey()) && "other_unsure".equals(answer.answerKey())),
                "AI-proposed 'other_unsure' must be suppressed");
        assertTrue(response.proposedAnswers().isEmpty(), "no root answer should remain");
    }

    @Test
    void userCanStillManuallySelectOtherUnsure() {
        ExpenseClassificationDTOs.ResolveResponse response = service.resolve(
                "missing-user",
                new ExpenseClassificationDTOs.ResolveRequest(
                        "2026-05-19.v1",
                        null,
                        false,
                        false,
                        List.of(new ExpenseClassificationDTOs.Answer("root", "other_unsure", "USER", null, null, true)),
                        List.of()
                )
        );

        assertEquals("RESOLVED", response.state());
        assertNotNull(response.result());
        assertEquals("OTHER_OR_UNCLEAR", response.result().resultKey());
        assertEquals("finance_review_fallback", response.result().accountKey());
        assertEquals("9998", response.result().accountNumber());
        assertTrue(response.result().requiresFinanceReview());
    }

    @Test
    @TestTransaction
    void persistsAuditMetadataForSubmittedClassification() {
        Expense expense = new Expense();
        expense.setUuid(UUID.randomUUID().toString());
        expense.setUseruuid("missing-user");
        expense.setAmount(450.0);
        expense.setAccount("placeholder");
        expense.setAccountname("placeholder");
        expense.setDescription("Gift");
        expense.setExpensedate(LocalDate.now());
        expense.setDatecreated(LocalDate.now());
        expense.setStatus("CREATED");
        expense.setClassification(new ExpenseClassificationDTOs.Submission(
                "2026-05-19.v1",
                "CLIENT_GIFT",
                "client_gift",
                "analysis-1",
                true,
                false,
                false,
                List.of(
                        new ExpenseClassificationDTOs.Answer("root", "gift", "AI", 0.91, "flower shop", true),
                        new ExpenseClassificationDTOs.Answer("gift_recipient", "client", "USER", null, null, true)
                ),
                List.of()
        ));

        service.applyResolvedAccount(expense);
        expense.persist();
        service.persistSubmittedClassification(expense);

        ExpenseClassification row = ExpenseClassification.find("expenseUuid", expense.getUuid()).firstResult();
        assertNotNull(row);
        assertEquals("CLIENT_GIFT", row.decisionResultKey);
        assertEquals("4008", row.accountNumber);
        assertTrue(row.aiUsed);
        assertFalse(row.requiresFinanceReview);
    }
}
