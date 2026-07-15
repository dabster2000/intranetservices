package dk.trustworks.intranet.aggregates.practices.services;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PracticeRevenueMaterializationServiceTest {
    @Test void everyMaterializationQueryCarriesTheConfiguredCancellationBound(){
        var service=new PracticeRevenueMaterializationService();
        service.em=mock(EntityManager.class);
        service.queryTimeout=java.time.Duration.ofMinutes(2);
        Query query=mock(Query.class);
        when(service.em.createNativeQuery("SELECT 1")).thenReturn(query);
        when(query.setHint("jakarta.persistence.query.timeout",120_000)).thenReturn(query);

        assertEquals(query,service.nativeQuery("SELECT 1"));

        verify(query).setHint("jakarta.persistence.query.timeout",120_000);
    }

    @Test void validatesPortfolioConservationBeforePublication(){
        var service=new PracticeRevenueMaterializationService();
        assertDoesNotThrow(()->service.validate(candidate("10.00","10.00")));
        assertThrows(PracticeRevenueMaterializationService.StructuralValidationException.class,
                ()->service.validate(candidate("10.00","9.99")));
    }

    @Test void persistedEvidenceKeepsOnlyManualRowsConfirmed(){
        var manual = PracticeRevenueMaterializationService.evidenceForStoredAttributions(List.of(
                stored("MANUAL", "INVOICE", "INTERNAL")));
        assertEquals(PracticeRevenueAllocationService.SourceTier.PERSISTED, manual.tier());
        assertEquals(PracticeRevenueAllocationService.AttributionSource.PERSISTED_MANUAL, manual.source());
        assertEquals(PracticeRevenueAllocationService.AttributionStatus.CONFIRMED, manual.attributionStatus());

        var automatic = PracticeRevenueMaterializationService.evidenceForStoredAttributions(List.of(
                stored("AUTO", "PHANTOM", "INTERNAL")));
        assertEquals(PracticeRevenueAllocationService.SourceTier.PHANTOM_AUTO, automatic.tier());
        assertEquals(PracticeRevenueAllocationService.AttributionSource.PHANTOM_AUTO, automatic.source());
        assertEquals(PracticeRevenueAllocationService.AttributionStatus.ESTIMATED, automatic.attributionStatus());
    }

    @Test void mixedOrMalformedStoredEvidenceFailsClosed(){
        var mixed = PracticeRevenueMaterializationService.evidenceForStoredAttributions(List.of(
                stored("MANUAL", "INVOICE", "INTERNAL"), stored("AUTO", "INVOICE", "INTERNAL")));
        assertEquals(PracticeRevenueAllocationService.EvidenceState.INVALID, mixed.state());
        assertEquals(PracticeRevenueAllocationService.ReasonCode.CONTRADICTORY_EVIDENCE, mixed.reason());

        var missingType = PracticeRevenueMaterializationService.evidenceForStoredAttributions(List.of(
                stored("MANUAL", "INVOICE", null)));
        assertEquals(PracticeRevenueAllocationService.EvidenceState.PRESENT, missingType.state());
        assertEquals(null, missingType.candidates().getFirst().consultantType());
    }

    @Test void costCertificateRequiresAnExactSuccessfulSourceVector(){
        var certificate = new PracticeRevenueMaterializationService.CostCertificate("NO_CHANGE",
                BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3), BigInteger.valueOf(4),
                BigInteger.valueOf(5), "vector");
        assertTrue(certificate.matches(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3),
                BigInteger.valueOf(4), BigInteger.valueOf(5), "vector"));
        assertFalse(certificate.matches(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3),
                BigInteger.valueOf(4), BigInteger.valueOf(6), "vector"));
        var failed = new PracticeRevenueMaterializationService.CostCertificate("FAILED",
                BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3), BigInteger.valueOf(4),
                BigInteger.valueOf(5), "vector");
        assertFalse(failed.matches(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3),
                BigInteger.valueOf(4), BigInteger.valueOf(5), "vector"));
    }

    @Test void missingGlEvidenceDoesNotFabricateAZeroReconciliation(){
        var service = new PracticeRevenueMaterializationService();
        var window = new PracticeRevenueMaterializationService.Window(false, "NO_WINDOW",
                null, null, null, null, null);
        var summary = service.summarize(0, List.of(), List.of(), List.of(),
                LocalDate.parse("2021-07-01"), LocalDate.parse("2026-06-30"), window, window);
        assertNull(summary.glControlTotal());
        assertNull(summary.reconciliationGap());
    }

    @Test void exactProspectiveDeliveryLineageIsConsumedAtTheDeliveryTier(){
        var raw = delivery(null, null, new BigDecimal("2.000000"), new BigDecimal("1000.000000"));
        String distribution = PracticeRevenueMaterializationService.deliveryDistributionFingerprint(List.of(raw));
        String item = PracticeRevenueMaterializationService.deliveryItemFingerprint(raw, distribution);
        var exact = delivery(item, distribution, raw.itemHours(), raw.itemRate());

        var evidence = PracticeRevenueMaterializationService.prospectiveEvidenceForStoredRows(
                List.of(exact), new PracticeRevenueAllocationService());

        assertEquals(PracticeRevenueAllocationService.EvidenceState.PRESENT, evidence.state());
        assertEquals(PracticeRevenueAllocationService.SourceTier.PROSPECTIVE_DELIVERY_LINEAGE, evidence.tier());
        assertEquals(PracticeRevenueAllocationService.AttributionStatus.ESTIMATED, evidence.attributionStatus());
        assertEquals("consultant", evidence.candidates().getFirst().consultantUuid());
        assertEquals(PracticeRevenueAllocationService.PracticeResolutionMethod.DATED_DELIVERY,
                evidence.candidates().getFirst().practiceResolutionMethod());
    }

    @Test void staleProspectiveDeliveryLineageStopsInsteadOfFallingBack(){
        var raw = delivery(null, null, new BigDecimal("2.000000"), new BigDecimal("1000.000000"));
        String distribution = PracticeRevenueMaterializationService.deliveryDistributionFingerprint(List.of(raw));
        String item = PracticeRevenueMaterializationService.deliveryItemFingerprint(raw, distribution);
        var stale = delivery(item, distribution, new BigDecimal("3.000000"), raw.itemRate());

        var evidence = PracticeRevenueMaterializationService.prospectiveEvidenceForStoredRows(
                List.of(stale), new PracticeRevenueAllocationService());

        assertEquals(PracticeRevenueAllocationService.EvidenceState.INVALID, evidence.state());
        assertEquals(PracticeRevenueAllocationService.ReasonCode.DELIVERY_EVIDENCE_AMBIGUOUS,
                evidence.reason());
    }

    @Test void exactByteIdenticalCreditCopyInheritsTheSourceAllocation(){
        var valuation = new PracticeRevenueValuationService();
        var allocation = new PracticeRevenueAllocationService();
        var sourceItem = valuedItem(valuation, "source-document", "source-item",
                PracticeRevenueValuationService.DocumentType.INVOICE);
        var sourceEvidence = new PracticeRevenueAllocationService.SourceEvidence(
                PracticeRevenueAllocationService.SourceTier.HUMAN,
                PracticeRevenueAllocationService.EvidenceState.PRESENT,
                PracticeRevenueAllocationService.AttributionSource.HUMAN,
                PracticeRevenueAllocationService.AttributionStatus.CONFIRMED,
                false,
                List.of(new PracticeRevenueAllocationService.RecipientCandidate(
                        "assignment", "consultant", "PM",
                        PracticeRevenueAllocationService.ConsultantType.INTERNAL,
                        LocalDate.parse("2026-02-01"), LocalDate.parse("2026-03-01"),
                        "HUMAN", BigDecimal.ONE,
                        PracticeRevenueAllocationService.PracticeResolutionMethod.DATED_DELIVERY,
                        false, false, null, null, null, false)),
                PracticeRevenueAllocationService.ReasonCode.NONE);
        var sourceAllocation = allocation.allocate(new PracticeRevenueAllocationService.AllocationRequest(
                sourceItem, PracticeRevenueValuationService.DocumentType.INVOICE, List.of(sourceEvidence)));
        var creditItem = valuedItem(valuation, "credit-document", "credit-item",
                PracticeRevenueValuationService.DocumentType.CREDIT_NOTE);
        var sourceProof = creditProof("source-item", "NONE", null);
        var proofWithoutFingerprint = creditProof("source-item", "BYTE_IDENTICAL", null);
        String fingerprint = PracticeRevenueMaterializationService.creditFingerprint(
                "source-item", proofWithoutFingerprint, new BigDecimal("100.000000000000"));
        var proof = creditProof("source-item", "BYTE_IDENTICAL", fingerprint);

        var inherited = PracticeRevenueMaterializationService.creditEvidence(creditItem, proof,
                Map.of("source-item", sourceAllocation), Map.of("source-item", sourceProof));
        var result = allocation.allocate(new PracticeRevenueAllocationService.AllocationRequest(
                creditItem, PracticeRevenueValuationService.DocumentType.CREDIT_NOTE, List.of(inherited)));

        assertEquals(PracticeRevenueAllocationService.EvidenceState.PRESENT, inherited.state());
        assertEquals(new BigDecimal("-100.00"), result.allocations().getFirst().allocationDkk());
        assertEquals(PracticeRevenueAllocationService.SegmentId.PM,
                result.allocations().getFirst().segmentId());
        assertTrue(result.allocations().getFirst().inheritedCreditResolution());
    }

    @Test void staleCreditCopyFingerprintFailsClosed(){
        var valuation = new PracticeRevenueValuationService();
        var creditItem = valuedItem(valuation, "credit-document", "credit-item",
                PracticeRevenueValuationService.DocumentType.CREDIT_NOTE);
        var evidence = PracticeRevenueMaterializationService.creditEvidence(creditItem,
                creditProof("source-item", "BYTE_IDENTICAL", "stale"), Map.of(),
                Map.of("source-item", creditProof("source-item", "NONE", null)));

        assertEquals(PracticeRevenueAllocationService.EvidenceState.INVALID, evidence.state());
        assertEquals(PracticeRevenueAllocationService.ReasonCode.ATTRIBUTION_INVALID, evidence.reason());
    }

    @Test void exactCreditRuleCorrelationRequiresOneSameCategoryConsultantAndExactKey(){
        var valuation=new PracticeRevenueValuationService();
        var allocation=new PracticeRevenueAllocationService();
        var sourceItem=correlatedItem(valuation,"source-document","source-item",
                PracticeRevenueValuationService.DocumentType.INVOICE,"consultant","calc","rule");
        var creditItem=correlatedItem(valuation,"credit-document","credit-item",
                PracticeRevenueValuationService.DocumentType.CREDIT_NOTE,"consultant","calc","rule");
        var sourceAllocation=allocation.allocate(new PracticeRevenueAllocationService.AllocationRequest(
                sourceItem,PracticeRevenueValuationService.DocumentType.INVOICE,
                List.of(baseDistribution("consultant","PM"))));
        var sourceProof=correlationProof("source-item","source-document",null,
                "consultant","calc","rule");
        var creditProof=correlationProof("credit-item","credit-document","source-document",
                "consultant","calc","rule");

        var evidence=PracticeRevenueMaterializationService.exactRuleCreditEvidence(
                creditItem,creditProof,Map.of("source-item",sourceAllocation),
                Map.of("source-item",sourceProof,"credit-item",creditProof),
                Map.of("source-item",sourceItem,"credit-item",creditItem));

        assertEquals(PracticeRevenueAllocationService.SourceTier.CREDIT_EXACT_RULE,evidence.tier());
        assertEquals(PracticeRevenueAllocationService.EvidenceState.PRESENT,evidence.state());
        var result=allocation.allocate(new PracticeRevenueAllocationService.AllocationRequest(
                creditItem,PracticeRevenueValuationService.DocumentType.CREDIT_NOTE,List.of(evidence)));
        assertEquals(new BigDecimal("-100.00"),result.allocations().getFirst().allocationDkk());
    }

    @Test void ambiguousExactCreditRuleCorrelationStopsInsteadOfChoosingByRowOrder(){
        var valuation=new PracticeRevenueValuationService();
        var creditItem=correlatedItem(valuation,"credit-document","credit-item",
                PracticeRevenueValuationService.DocumentType.CREDIT_NOTE,"consultant","calc","rule");
        var sourceA=correlatedItem(valuation,"source-document","source-a",
                PracticeRevenueValuationService.DocumentType.INVOICE,"consultant","calc","rule");
        var sourceB=correlatedItem(valuation,"source-document","source-b",
                PracticeRevenueValuationService.DocumentType.INVOICE,"consultant","calc","rule");
        var creditProof=correlationProof("credit-item","credit-document","source-document",
                "consultant","calc","rule");

        var evidence=PracticeRevenueMaterializationService.exactRuleCreditEvidence(
                creditItem,creditProof,Map.of(),
                Map.of("source-a",correlationProof("source-a","source-document",null,"consultant","calc","rule"),
                        "source-b",correlationProof("source-b","source-document",null,"consultant","calc","rule"),
                        "credit-item",creditProof),
                Map.of("source-a",sourceA,"source-b",sourceB,"credit-item",creditItem));

        assertEquals(PracticeRevenueAllocationService.EvidenceState.INVALID,evidence.state());
        assertEquals(PracticeRevenueAllocationService.SourceTier.CREDIT_EXACT_RULE,evidence.tier());
    }

    @Test void absentExactCorrelationAdvancesToTheCompleteSourceInvoiceDistribution(){
        var valuation=new PracticeRevenueValuationService();
        var allocation=new PracticeRevenueAllocationService();
        var sourceInput=new PracticeRevenueValuationService.ItemInput("source-item",
                PracticeRevenueValuationService.ItemOrigin.BASE,"1","100","consultant",true,
                null,null,null,false,null,null,null,null,null,null,null,null);
        var sourceDocument=new PracticeRevenueValuationService.DocumentInput("source-document","company",
                PracticeRevenueValuationService.DocumentType.INVOICE,"CREATED",false,
                LocalDate.parse("2026-02-17"),"DKK","0",List.of(sourceInput),
                List.of(new PracticeRevenueValuationService.GlEntry("voucher","company",2025,
                        "BOOKED",1,0,"REVENUE","-100","source-document")),List.of());
        var sourceValuation=valuation.value(List.of(sourceDocument)).documents().getFirst();
        var sourceItem=sourceValuation.items().getFirst();
        var sourceAllocation=allocation.allocate(new PracticeRevenueAllocationService.AllocationRequest(
                sourceItem,PracticeRevenueValuationService.DocumentType.INVOICE,
                List.of(sourceEvidence("consultant","PM",BigDecimal.ONE,false))));
        var creditItem=valuedItem(valuation,"credit-document","credit-item",
                PracticeRevenueValuationService.DocumentType.CREDIT_NOTE);
        var creditProof=correlationProof("credit-item","credit-document","source-document",
                "consultant",null,null);

        var exact=PracticeRevenueMaterializationService.exactRuleCreditEvidence(creditItem,creditProof,
                Map.of("source-item",sourceAllocation),Map.of("credit-item",creditProof),
                Map.of("source-item",sourceItem,"credit-item",creditItem));
        var invoice=PracticeRevenueMaterializationService.completeSourceInvoiceDistribution(
                sourceValuation,List.of(sourceAllocation));

        assertEquals(PracticeRevenueAllocationService.EvidenceState.ABSENT,exact.state());
        assertEquals(PracticeRevenueAllocationService.EvidenceState.PRESENT,invoice.state());
        assertEquals(PracticeRevenueAllocationService.SourceTier.CREDIT_SOURCE_INVOICE,invoice.tier());
    }

    @Test void commercialAdjustmentUsesPositiveNetBaseDistributionBeforeCentRounding(){
        var valuation = new PracticeRevenueValuationService();
        var allocation = new PracticeRevenueAllocationService();
        var pm = sourceEvidence("pm", "PM", BigDecimal.ONE, false);
        var dev = sourceEvidence("dev", "DEV", BigDecimal.ONE, false);
        var pmResult = allocation.allocate(new PracticeRevenueAllocationService.AllocationRequest(
                valuedItem(valuation, "document", "pm-item", "100.000000",
                        PracticeRevenueValuationService.DocumentType.INVOICE),
                PracticeRevenueValuationService.DocumentType.INVOICE, List.of(pm)));
        var devResult = allocation.allocate(new PracticeRevenueAllocationService.AllocationRequest(
                valuedItem(valuation, "document", "dev-item", "900.000000",
                        PracticeRevenueValuationService.DocumentType.INVOICE),
                PracticeRevenueValuationService.DocumentType.INVOICE, List.of(dev)));
        var distribution = PracticeRevenueMaterializationService.baseDistributionEvidence(
                List.of(pmResult, devResult));
        var adjustment = adjustmentItem(valuation, "document", "discount", "-100.000000");

        var result = allocation.allocate(new PracticeRevenueAllocationService.AllocationRequest(
                adjustment, PracticeRevenueValuationService.DocumentType.INVOICE,
                List.of(distribution)));

        assertEquals(2, result.allocations().size());
        assertEquals(new BigDecimal("-10.00"), result.allocations().stream()
                .filter(row -> row.segmentId() == PracticeRevenueAllocationService.SegmentId.PM)
                .findFirst().orElseThrow().allocationDkk());
        assertEquals(new BigDecimal("-90.00"), result.allocations().stream()
                .filter(row -> row.segmentId() == PracticeRevenueAllocationService.SegmentId.DEV)
                .findFirst().orElseThrow().allocationDkk());
    }

    @Test void baseDistributionRetainsItsExactUnassignedShare(){
        var valuation = new PracticeRevenueValuationService();
        var allocation = new PracticeRevenueAllocationService();
        var partial = sourceEvidence("pm", "PM", new BigDecimal("0.800000000000000000"), true);
        var base = allocation.allocate(new PracticeRevenueAllocationService.AllocationRequest(
                valuedItem(valuation, "document", "base", "100.000000",
                        PracticeRevenueValuationService.DocumentType.INVOICE),
                PracticeRevenueValuationService.DocumentType.INVOICE, List.of(partial)));
        var distribution = PracticeRevenueMaterializationService.baseDistributionEvidence(List.of(base));

        var result = allocation.allocate(new PracticeRevenueAllocationService.AllocationRequest(
                adjustmentItem(valuation, "document", "fee", "10.000000"),
                PracticeRevenueValuationService.DocumentType.INVOICE, List.of(distribution)));

        assertEquals(new BigDecimal("8.00"), result.allocations().stream()
                .filter(row -> row.segmentId() == PracticeRevenueAllocationService.SegmentId.PM)
                .findFirst().orElseThrow().allocationDkk());
        assertEquals(new BigDecimal("2.00"), result.allocations().stream()
                .filter(row -> row.segmentId() == PracticeRevenueAllocationService.SegmentId.UNASSIGNED)
                .findFirst().orElseThrow().allocationDkk());
    }

    @Test void completeSourceInvoiceDistributionCanDriveAnInvoiceLevelCreditResidual(){
        var valuation = new PracticeRevenueValuationService();
        var allocation = new PracticeRevenueAllocationService();
        var pmItem = new PracticeRevenueValuationService.ItemInput("pm-item",
                PracticeRevenueValuationService.ItemOrigin.BASE, "1", "100", "pm", true,
                null, null, null, false, null, null, null, null, null, null, null, null);
        var devItem = new PracticeRevenueValuationService.ItemInput("dev-item",
                PracticeRevenueValuationService.ItemOrigin.BASE, "1", "900", "dev", true,
                null, null, null, false, null, null, null, null, null, null, null, null);
        var sourceDocument = new PracticeRevenueValuationService.DocumentInput("source-document", "company",
                PracticeRevenueValuationService.DocumentType.INVOICE, "CREATED", false,
                LocalDate.parse("2026-02-17"), "DKK", "0", List.of(pmItem, devItem),
                List.of(new PracticeRevenueValuationService.GlEntry("voucher", "company", 2025,
                        "BOOKED", 1, 0, "REVENUE", "-1000", "source-document")), List.of());
        var valuedSource = valuation.value(List.of(sourceDocument)).documents().getFirst();
        var byItem = valuedSource.items().stream().collect(java.util.stream.Collectors.toMap(
                PracticeRevenueValuationService.ItemControl::sourceItemUuid, item -> item));
        var pm = allocation.allocate(new PracticeRevenueAllocationService.AllocationRequest(
                byItem.get("pm-item"), PracticeRevenueValuationService.DocumentType.INVOICE,
                List.of(sourceEvidence("pm", "PM", BigDecimal.ONE, false))));
        var dev = allocation.allocate(new PracticeRevenueAllocationService.AllocationRequest(
                byItem.get("dev-item"), PracticeRevenueValuationService.DocumentType.INVOICE,
                List.of(sourceEvidence("dev", "DEV", BigDecimal.ONE, false))));
        var inherited = PracticeRevenueMaterializationService.completeSourceInvoiceDistribution(
                valuedSource, List.of(pm, dev));

        var result = allocation.allocate(new PracticeRevenueAllocationService.AllocationRequest(
                valuedItem(valuation, "credit-document", "credit-item", "100.000000",
                        PracticeRevenueValuationService.DocumentType.CREDIT_NOTE),
                PracticeRevenueValuationService.DocumentType.CREDIT_NOTE, List.of(inherited)));

        assertEquals(PracticeRevenueAllocationService.EvidenceState.PRESENT, inherited.state());
        assertEquals(new BigDecimal("-10.00"), result.allocations().stream()
                .filter(row -> row.segmentId() == PracticeRevenueAllocationService.SegmentId.PM)
                .findFirst().orElseThrow().allocationDkk());
        assertEquals(new BigDecimal("-90.00"), result.allocations().stream()
                .filter(row -> row.segmentId() == PracticeRevenueAllocationService.SegmentId.DEV)
                .findFirst().orElseThrow().allocationDkk());
        assertTrue(result.allocations().stream().allMatch(
                PracticeRevenueAllocationService.Allocation::inheritedCreditResolution));
    }

    @Test void partialSourceInvoiceDistributionCannotBeInheritedByACredit(){
        var valuation = new PracticeRevenueValuationService();
        var sourceItem = new PracticeRevenueValuationService.ItemInput("source-item",
                PracticeRevenueValuationService.ItemOrigin.BASE, "1", "100", "pm", true,
                null, null, null, false, null, null, null, null, null, null, null, null);
        var sourceDocument = new PracticeRevenueValuationService.DocumentInput("source-document", "company",
                PracticeRevenueValuationService.DocumentType.INVOICE, "CREATED", false,
                LocalDate.parse("2026-02-17"), "DKK", "0", List.of(sourceItem),
                List.of(new PracticeRevenueValuationService.GlEntry("voucher", "company", 2025,
                        "BOOKED", 1, 0, "REVENUE", "-100", "source-document")), List.of());
        var valuedSource = valuation.value(List.of(sourceDocument)).documents().getFirst();

        var evidence = PracticeRevenueMaterializationService.completeSourceInvoiceDistribution(
                valuedSource, List.of());

        assertEquals(PracticeRevenueAllocationService.EvidenceState.INVALID, evidence.state());
        assertEquals(PracticeRevenueAllocationService.SourceTier.CREDIT_SOURCE_INVOICE, evidence.tier());
    }

    @Test void directSelfBilledAssignmentsPreserveHumanWorkPeriodsAndFractions(){
        var evidence=PracticeRevenueMaterializationService.selfBilledEvidence(List.of(
                selfBilled("a1","pm",2025,12,"-20.00","PM","HUMAN"),
                selfBilled("a2","dev",2026,1,"-80.00","DEV","HUMAN")));

        assertEquals(PracticeRevenueAllocationService.EvidenceState.PRESENT,evidence.state());
        assertEquals(PracticeRevenueAllocationService.AttributionStatus.CONFIRMED,evidence.attributionStatus());
        assertEquals(new BigDecimal("0.200000000000000000"),evidence.candidates().getFirst().rawFraction());
        assertEquals(LocalDate.parse("2025-12-01"),evidence.candidates().getFirst().deliveryStart());
        assertEquals(LocalDate.parse("2026-01-01"),evidence.candidates().getFirst().deliveryEndExclusive());
    }

    @Test void contradictorySelfBilledAssignmentSignFailsClosed(){
        var evidence=PracticeRevenueMaterializationService.selfBilledEvidence(List.of(
                selfBilled("a1","pm",2025,12,"20.00","PM","HUMAN")));

        assertEquals(PracticeRevenueAllocationService.EvidenceState.INVALID,evidence.state());
        assertEquals(PracticeRevenueAllocationService.ReasonCode.ATTRIBUTION_INVALID,evidence.reason());
    }

    private static PracticeRevenueMaterializationService.StoredAttribution stored(
            String source, String documentType, String consultantType) {
        return new PracticeRevenueMaterializationService.StoredAttribution("a-" + source,
                "consultant", new BigDecimal("100.000000"), source, "PM", consultantType,
                "HISTORICAL", documentType);
    }

    private static PracticeRevenueMaterializationService.StoredDelivery delivery(
            String itemFingerprint, String distributionFingerprint, BigDecimal itemHours,
            BigDecimal itemRate) {
        return new PracticeRevenueMaterializationService.StoredDelivery("item", "work", "registrant",
                "consultant", LocalDate.parse("2026-01-02"), "task", "project", "contract",
                "contract-project", "contract-consultant", new BigDecimal("2.000000"),
                new BigDecimal("1000.000000"), new BigDecimal("2000.000000000000"), "RESOLVED",
                "PRACTICE_DELIVERY_LINEAGE_V1", itemFingerprint, distributionFingerprint, itemHours,
                itemRate, "BASE", "rule", "PM", "INTERNAL", "HISTORY");
    }

    private static PracticeRevenueValuationService.ItemControl valuedItem(
            PracticeRevenueValuationService valuation, String documentUuid, String itemUuid,
            PracticeRevenueValuationService.DocumentType type) {
        return valuedItem(valuation, documentUuid, itemUuid, "100.000000", type);
    }

    private static PracticeRevenueValuationService.ItemControl valuedItem(
            PracticeRevenueValuationService valuation, String documentUuid, String itemUuid,
            String rate, PracticeRevenueValuationService.DocumentType type) {
        var item = new PracticeRevenueValuationService.ItemInput(itemUuid,
                PracticeRevenueValuationService.ItemOrigin.BASE, "1.000000", rate,
                "consultant", true, null, null, null, false,
                null, null, null, null, null, null, null, null);
        var document = new PracticeRevenueValuationService.DocumentInput(documentUuid, "company", type,
                "CREATED", false, LocalDate.parse("2026-02-17"), "DKK", "0",
                List.of(item), List.of(), List.of());
        return valuation.value(List.of(document)).documents().getFirst().items().getFirst();
    }

    private static PracticeRevenueValuationService.ItemControl adjustmentItem(
            PracticeRevenueValuationService valuation, String documentUuid, String itemUuid, String rate) {
        var item = new PracticeRevenueValuationService.ItemInput(itemUuid,
                PracticeRevenueValuationService.ItemOrigin.CALCULATED, "1.000000", rate,
                null, false, "calculation", "rule", "Adjustment", true,
                "pricing-v1", "step", 1, "DISCOUNT", "input", "output",
                new BigDecimal(rate).setScale(12), "algorithm-v1");
        var document = new PracticeRevenueValuationService.DocumentInput(documentUuid, "company",
                PracticeRevenueValuationService.DocumentType.INVOICE, "CREATED", false,
                LocalDate.parse("2026-02-17"), "DKK", "0", List.of(item), List.of(), List.of());
        return valuation.value(List.of(document)).documents().getFirst().items().getFirst();
    }

    private static PracticeRevenueAllocationService.SourceEvidence sourceEvidence(
            String consultant, String practice, BigDecimal share, boolean residualPermitted) {
        return new PracticeRevenueAllocationService.SourceEvidence(
                PracticeRevenueAllocationService.SourceTier.HUMAN,
                PracticeRevenueAllocationService.EvidenceState.PRESENT,
                PracticeRevenueAllocationService.AttributionSource.HUMAN,
                PracticeRevenueAllocationService.AttributionStatus.CONFIRMED,
                residualPermitted,
                List.of(new PracticeRevenueAllocationService.RecipientCandidate(
                        "source-" + consultant, consultant, practice,
                        PracticeRevenueAllocationService.ConsultantType.INTERNAL,
                        LocalDate.parse("2026-02-01"), LocalDate.parse("2026-03-01"),
                        "HUMAN", share,
                        PracticeRevenueAllocationService.PracticeResolutionMethod.DATED_DELIVERY,
                        false, false, null, null, null, false)),
                PracticeRevenueAllocationService.ReasonCode.NONE);
    }

    private static PracticeRevenueAllocationService.SourceEvidence baseDistribution(
            String consultant,String practice){
        return new PracticeRevenueAllocationService.SourceEvidence(
                PracticeRevenueAllocationService.SourceTier.BASE_DISTRIBUTION,
                PracticeRevenueAllocationService.EvidenceState.PRESENT,
                PracticeRevenueAllocationService.AttributionSource.BASE_DISTRIBUTION,
                PracticeRevenueAllocationService.AttributionStatus.CONFIRMED,false,
                List.of(new PracticeRevenueAllocationService.RecipientCandidate(
                        "base-"+consultant,consultant,practice,
                        PracticeRevenueAllocationService.ConsultantType.INTERNAL,
                        LocalDate.parse("2026-02-01"),LocalDate.parse("2026-03-01"),
                        "BASE_DISTRIBUTION",BigDecimal.ONE,
                        PracticeRevenueAllocationService.PracticeResolutionMethod.DATED_DELIVERY,
                        false,false,null,null,null,false)),
                PracticeRevenueAllocationService.ReasonCode.NONE);
    }

    private static PracticeRevenueValuationService.ItemControl correlatedItem(
            PracticeRevenueValuationService valuation,String documentUuid,String itemUuid,
            PracticeRevenueValuationService.DocumentType type,String consultant,String calculationRef,
            String ruleId){
        var item=new PracticeRevenueValuationService.ItemInput(itemUuid,
                PracticeRevenueValuationService.ItemOrigin.CALCULATED,"1.000000","100.000000",
                consultant,true,calculationRef,ruleId,"Adjustment",false,
                null,null,null,null,null,null,null,null);
        var document=new PracticeRevenueValuationService.DocumentInput(documentUuid,"company",type,
                "CREATED",false,LocalDate.parse("2026-02-17"),"DKK","0",
                List.of(item),List.of(),List.of());
        return valuation.value(List.of(document)).documents().getFirst().items().getFirst();
    }

    private static PracticeRevenueMaterializationService.CreditEvidence correlationProof(
            String itemUuid,String itemDocumentUuid,String linkedSourceDocumentUuid,String consultant,
            String calculationRef,String ruleId){
        return new PracticeRevenueMaterializationService.CreditEvidence(itemUuid,null,"NONE",null,
                null,null,null,itemDocumentUuid,linkedSourceDocumentUuid,new BigDecimal("1.000000"),
                new BigDecimal("100.000000"),"CALCULATED",consultant,calculationRef,ruleId,null,null);
    }

    private static PracticeRevenueMaterializationService.CreditEvidence creditProof(
            String sourceItemUuid, String kind, String fingerprint) {
        return new PracticeRevenueMaterializationService.CreditEvidence("credit-item", sourceItemUuid,
                kind, "SOURCE_ITEM", new BigDecimal("1.000000000000000000"),
                new BigDecimal("100.000000000000"), fingerprint,
                "NONE".equals(kind) ? "source-document" : "credit-document", "source-document",
                new BigDecimal("1.000000"), new BigDecimal("100.000000"), "BASE",
                "consultant", null, null, null, null);
    }

    private static PracticeRevenueMaterializationService.StoredSelfBilledAssignment selfBilled(
            String uuid,String consultant,int year,int month,String share,String practice,String source){
        return new PracticeRevenueMaterializationService.StoredSelfBilledAssignment("phantom-item",uuid,
                consultant,year,month,new BigDecimal(share),source,practice,"INTERNAL","HISTORY",
                new BigDecimal("100.000000000000"),new BigDecimal("-100.000000000000"));
    }
    private static PracticeRevenueMaterializationService.BuildCandidate candidate(String item,String allocation){
        var window=new PracticeRevenueMaterializationService.Window(false,"NO_WINDOW",null,null,null,null,null);
        return new PracticeRevenueMaterializationService.BuildCandidate(LocalDateTime.parse("2026-07-15T00:00:00"),
                LocalDate.parse("2021-07-01"),LocalDate.parse("2026-06-01"),window,window,0,
                List.of(),List.of(),List.of(),0,0,0,0,0,0,0,0,
                new BigDecimal(item),new BigDecimal(allocation),BigDecimal.ZERO.setScale(2),BigDecimal.ZERO.setScale(2));
    }
}
