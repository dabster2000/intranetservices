package dk.trustworks.intranet.aggregates.practices.services;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueValuationService.*;
import static org.junit.jupiter.api.Assertions.*;

class PracticeRevenueValuationServiceTest {

    private final PracticeRevenueValuationService service = new PracticeRevenueValuationService();

    @Test
    void recognizesOnlyCreatedExternalPopulationAtInvoiceDateMonth() {
        DocumentInput invoice = document("i", DocumentType.INVOICE, "CREATED", false, List.of(base("a", "2", "50", "u")), List.of(), List.of());
        DocumentInput phantom = document("p", DocumentType.PHANTOM, "CREATED", false, List.of(base("p1", "1", "1", "u")), List.of(), List.of());
        DocumentInput credit = document("c", DocumentType.CREDIT_NOTE, "CREATED", false, List.of(base("c1", "1", "1", "u")), List.of(), List.of());
        DocumentInput draft = document("d", DocumentType.INVOICE, "DRAFT", false, List.of(base("d1", "1", "1", "u")), List.of(), List.of());
        DocumentInput internalCredit = document("ic", DocumentType.CREDIT_NOTE, "CREATED", true, List.of(base("ic1", "1", "1", "u")), List.of(), List.of());

        ValuationBatch batch = service.value(List.of(invoice, phantom, credit, draft, internalCredit));

        assertEquals(3, batch.recognizedDocumentCount());
        assertEquals(2, batch.excludedDocumentCount());
        assertTrue(batch.documents().stream().allMatch(value -> value.recognizedMonth().equals(LocalDate.of(2026, 2, 1))));
    }

    @Test
    void uniqueRevenueGlIsSignedOnceAndConservedAcrossItems() {
        GlEntry gl = gl("K1", "-150.0049");
        DocumentInput input = document("i", DocumentType.INVOICE, "CREATED", false,
                List.of(base("a", "2", "50", "u1"), base("b", "1", "50", "u2")), List.of(gl), List.of());

        DocumentValuation value = service.value(List.of(input)).documents().getFirst();

        assertEquals(new BigDecimal("150.0049"), value.matchedRawGlDkk());
        assertEquals(new BigDecimal("150.00"), value.matchedGlCandidateCentDkk());
        assertEquals(new BigDecimal("150.00"), value.authoritativeControlDkk());
        assertEquals(new BigDecimal("150.00"), value.items().stream().map(ItemControl::itemControlDkk)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(1, value.items().stream().filter(ItemControl::documentRatioClosureRow).count());
        assertEquals(new BigDecimal("1.000000000000000000"), value.items().stream()
                .map(ItemControl::effectiveDocumentRatio).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void creditUsesNegativeDocumentSignWithoutFlippingGlTwice() {
        DocumentInput input = document("c", DocumentType.CREDIT_NOTE, "CREATED", false,
                List.of(base("a", "2", "50", "u1")), List.of(gl("K1", "100.0000")), List.of());

        ItemControl item = service.value(List.of(input)).documents().getFirst().items().getFirst();

        assertEquals(new BigDecimal("-100.000000000000"), item.signedNativeControl());
        assertEquals(new BigDecimal("-100.00"), item.itemControlDkk());
        assertEquals(ValuationStatus.CONFIRMED_GL, item.valuationStatus());
    }

    @Test
    void monthlyFxIsExactProvisionalAndNeverAuthoritative() {
        DocumentInput input = new DocumentInput("i", "co", DocumentType.INVOICE, "CREATED", false,
                LocalDate.of(2026, 2, 17), "EUR", "0", List.of(
                base("a", "1", "0.005", "u1"), base("b", "1", "0.005", "u2")),
                List.of(), List.of("7.444444445"));

        DocumentValuation value = service.value(List.of(input)).documents().getFirst();

        assertNull(value.authoritativeControlDkk());
        assertEquals(new BigDecimal("0.07"), value.provisionalControlDkk());
        assertEquals(new BigDecimal("0.07"), value.items().stream().map(ItemControl::itemControlDkk)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertTrue(value.items().stream().allMatch(item -> item.valuationStatus() == ValuationStatus.PROVISIONAL_MONTHLY_FX));
        assertTrue(value.items().stream().allMatch(ItemControl::fxNormalizationChanged));
    }

    @Test
    void noFxOneFallbackExists() {
        DocumentInput input = new DocumentInput("i", "co", DocumentType.INVOICE, "CREATED", false,
                LocalDate.of(2026, 2, 17), "EUR", "0", List.of(base("a", "1", "100", "u")),
                List.of(), List.of());

        DocumentValuation value = service.value(List.of(input)).documents().getFirst();

        assertEquals(ReasonCode.FX_RATE_MISSING, value.reasonCode());
        assertNull(value.items().getFirst().itemControlDkk());
    }

    @Test
    void nearZeroSignedDenominatorCreatesOneNonZeroControlledResidual() {
        DocumentInput input = document("i", DocumentType.INVOICE, "CREATED", false,
                List.of(base("a", "1", "100", "u1"), base("b", "1", "-99.999999", "u2")),
                List.of(gl("K", "-10.0000")), List.of());

        DocumentValuation value = service.value(List.of(input)).documents().getFirst();

        assertEquals(3, value.items().size());
        assertEquals(2, value.items().stream().filter(item -> item.valuationStatus() == ValuationStatus.CONTROLLED_BY_DOCUMENT_RESIDUAL).count());
        ItemControl residual = value.items().stream().filter(ItemControl::syntheticResidual).findFirst().orElseThrow();
        assertEquals(new BigDecimal("10.00"), residual.itemControlDkk());
        assertEquals(ReasonCode.NEAR_ZERO_SIGNED_NATIVE_DENOMINATOR, residual.reasonCode());
    }

    @Test
    void zeroGlCannotEraseOffsettingMovement() {
        DocumentInput input = new DocumentInput("i", "co", DocumentType.INVOICE, "CREATED", false,
                LocalDate.of(2026, 2, 17), "EUR", "0",
                List.of(base("a", "1", "100", "u1"), base("b", "1", "-100", "u2")),
                List.of(gl("K", "0")), List.of());

        DocumentValuation value = service.value(List.of(input)).documents().getFirst();

        assertEquals(ReasonCode.OFFSETTING_ITEM_CONTROL_UNAVAILABLE, value.reasonCode());
        assertTrue(value.items().stream().noneMatch(ItemControl::syntheticResidual));
        assertTrue(value.items().stream().allMatch(item -> item.itemControlDkk() == null));
    }

    @Test
    void zeroGlCannotCreateFalseZeroForMaterialNonDkkMovement() {
        DocumentInput input = new DocumentInput("i", "co", DocumentType.INVOICE, "CREATED", false,
                LocalDate.of(2026, 2, 17), "EUR", "0",
                List.of(base("a", "1", "100", "u1")), List.of(gl("K", "0")), List.of());

        DocumentValuation value = service.value(List.of(input)).documents().getFirst();

        assertEquals(ReasonCode.OFFSETTING_ITEM_CONTROL_UNAVAILABLE, value.reasonCode());
        assertNull(value.items().getFirst().itemControlDkk());
    }

    @Test
    void ordinaryHeaderMonetaryGapUsesUnassignedDocumentResidualOnlyWithGl() {
        ItemInput ordinary = base("a", "1", "100", "u");
        DocumentInput controlled = new DocumentInput("i", "co", DocumentType.INVOICE, "CREATED", false,
                LocalDate.of(2026, 2, 17), "DKK", "5", List.of(ordinary), List.of(gl("K", "-95")), List.of());
        DocumentInput provisional = new DocumentInput("j", "co", DocumentType.INVOICE, "CREATED", false,
                LocalDate.of(2026, 2, 17), "DKK", "5", List.of(ordinary), List.of(), List.of());

        DocumentValuation controlledValue = service.value(List.of(controlled)).documents().getFirst();
        DocumentValuation provisionalValue = service.value(List.of(provisional)).documents().getFirst();

        assertEquals(ReasonCode.HEADER_DISCOUNT_MONETARY_STRUCTURE_UNAVAILABLE, controlledValue.reasonCode());
        assertEquals(1, controlledValue.items().stream().filter(ItemControl::syntheticResidual).count());
        assertEquals(ReasonCode.HEADER_DISCOUNT_MONETARY_STRUCTURE_UNAVAILABLE, provisionalValue.reasonCode());
        assertTrue(provisionalValue.items().stream().allMatch(item -> item.itemControlDkk() == null));
    }

    @Test
    void exactPricingProvenanceAvoidsSecondHeaderApplication() {
        ItemInput base = base("a", "1", "100", "u");
        ItemInput discount = new ItemInput("d", ItemOrigin.CALCULATED, "1", "-5", null, false,
                "ref", "contract-rule", "Discount", true, "v1", "step", 1, "DISCOUNT",
                "in", "out", new BigDecimal("-5.000000000000"), "algo");
        DocumentInput input = new DocumentInput("i", "co", DocumentType.INVOICE, "CREATED", false,
                LocalDate.of(2026, 2, 17), "DKK", "5", List.of(base, discount),
                List.of(gl("K", "-95")), List.of());

        DocumentValuation value = service.value(List.of(input)).documents().getFirst();

        assertEquals(new BigDecimal("95.00"), value.authoritativeControlDkk());
        assertEquals(SourceStatus.COMPLETE, value.sourceStatus());
        assertEquals(ReasonCode.NONE, value.reasonCode());
    }

    @Test
    void inverseVoucherKeyCollisionIsNeverCopiedToTwoDocuments() {
        GlEntry gl = gl("K", "-100");
        DocumentInput invoice = document("i", DocumentType.INVOICE, "CREATED", false,
                List.of(base("a", "1", "100", "u")), List.of(gl), List.of());
        DocumentInput phantom = document("p", DocumentType.PHANTOM, "CREATED", false,
                List.of(base("b", "1", "100", "u")), List.of(gl), List.of());

        ValuationBatch batch = service.value(List.of(invoice, phantom));

        assertTrue(batch.documents().stream().allMatch(value -> value.reasonCode() == ReasonCode.MANUAL_PHANTOM_DUPLICATE_RISK));
        assertTrue(batch.documents().stream().flatMap(value -> value.items().stream()).allMatch(item -> item.itemControlDkk() == null));
    }

    @Test
    void conflictingStoredAccountingIdentifiersFailClosedBeforeGlAllocation() {
        DocumentInput input = new DocumentInput("i", "co", DocumentType.INVOICE, "CREATED", false,
                LocalDate.of(2026, 2, 17), "DKK", "0",
                List.of(base("a", "1", "100", "u")), List.of(gl("K", "-100")), List.of(),
                false, true);

        DocumentValuation value = service.value(List.of(input)).documents().getFirst();

        assertEquals(ReasonCode.GL_CONTROL_AMBIGUOUS, value.reasonCode());
        assertNull(value.authoritativeControlDkk());
        assertNull(value.items().getFirst().itemControlDkk());
    }

    @Test
    void dependencyOnlyHistoricalSourceParticipatesInInverseVoucherUniquenessWithoutRecognition() {
        GlEntry currentGl = gl("caller-label", "-100");
        GlEntry historyGl = new GlEntry("different-label", "co", 2025, "BOOKED", 42, 999,
                "REVENUE", "-100", "historical-voucher");
        DocumentInput current = document("i", DocumentType.INVOICE, "CREATED", false,
                List.of(base("a", "1", "100", "u")), List.of(currentGl), List.of());
        DocumentInput dependency = new DocumentInput("history", "co", DocumentType.INVOICE, "CREATED", false,
                LocalDate.of(2020, 1, 1), "DKK", "0", List.of(base("h", "1", "100", "u")),
                List.of(historyGl), List.of(), true);

        ValuationBatch batch = service.value(List.of(current, dependency));

        assertEquals(1, batch.recognizedDocumentCount());
        assertEquals(1, batch.documents().size());
        assertEquals(ReasonCode.GL_CONTROL_AMBIGUOUS, batch.documents().getFirst().reasonCode());
        assertNull(batch.documents().getFirst().items().getFirst().itemControlDkk());
    }

    @Test
    void phantomRequiresExactlyOneSourceItem() {
        DocumentInput input = document("p", DocumentType.PHANTOM, "CREATED", false,
                List.of(base("a", "1", "50", "u"), base("b", "1", "50", "u")),
                List.of(gl("K", "-100")), List.of());

        DocumentValuation value = service.value(List.of(input)).documents().getFirst();

        assertEquals(ReasonCode.PHANTOM_ITEM_GRAIN_INVALID, value.reasonCode());
        assertTrue(value.items().stream().allMatch(item -> item.valuationStatus() == ValuationStatus.UNAVAILABLE_PHANTOM_ITEM_GRAIN));
    }

    private static DocumentInput document(String uuid, DocumentType type, String status, boolean internal,
                                          List<ItemInput> items, List<GlEntry> gl, List<String> fx) {
        return new DocumentInput(uuid, "co", type, status, internal, LocalDate.of(2026, 2, 17),
                "DKK", "0", items, gl, fx);
    }

    private static ItemInput base(String uuid, String hours, String rate, String consultant) {
        return new ItemInput(uuid, ItemOrigin.BASE, hours, rate, consultant, consultant != null,
                null, null, null, false, null, null, null, null, null, null, null, null);
    }

    private static GlEntry gl(String key, String amount) {
        return new GlEntry(key, "co", 2025, "BOOKED", 42, 0,
                "REVENUE", amount, "voucher-42");
    }
}
