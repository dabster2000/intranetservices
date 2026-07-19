package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.model.PracticeRevenueItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PracticeRevenueMaterializationServiceTest {
    @Test void revenueCoverageEndsAtTheLatestCompletedCopenhagenMonth(){
        var coverage=PracticeRevenueMaterializationService.revenueCoverage(
                Instant.parse("2026-06-30T22:30:00Z"));

        assertEquals(YearMonth.of(2021,7),coverage.first());
        assertEquals(YearMonth.of(2026,6),coverage.last());
    }

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

    @Test void glReconciliationToleranceScalesWithTheAbsoluteControlTotal(){
        var service=new PracticeRevenueMaterializationService();
        // Small control 5,000 -> tolerance max(1.00, 0.50)=1.00: 1.00 at boundary passes, 1.01 fails.
        assertDoesNotThrow(()->service.validate(glCandidate("5000.00","5000.00","5000.00","1.00")));
        assertThrows(PracticeRevenueMaterializationService.StructuralValidationException.class,
                ()->service.validate(glCandidate("5000.00","5000.00","5000.00","1.01")));
        // Large control 100,000 -> tolerance max(1.00, 10.00)=10.00: 9.99 and 10.00 pass, 10.01 fails.
        assertDoesNotThrow(()->service.validate(glCandidate("100000.00","100000.00","100000.00","9.99")));
        assertDoesNotThrow(()->service.validate(glCandidate("100000.00","100000.00","100000.00","10.00")));
        assertThrows(PracticeRevenueMaterializationService.StructuralValidationException.class,
                ()->service.validate(glCandidate("100000.00","100000.00","100000.00","10.01")));
        // Negative control uses ABS for the tolerance basis.
        assertDoesNotThrow(()->service.validate(glCandidate("-100000.00","-100000.00","-100000.00","-10.00")));
        assertThrows(PracticeRevenueMaterializationService.StructuralValidationException.class,
                ()->service.validate(glCandidate("-100000.00","-100000.00","-100000.00","-10.01")));
    }

    @Test void glReconciliationIsSkippedWhenThereIsNoGlControlledSubset(){
        var service=new PracticeRevenueMaterializationService();
        assertDoesNotThrow(()->service.validate(candidate("10.00","10.00")));
    }

    @Test void persistedEvidenceKeepsManualAndOnlyVersionedAutoEvidence(){
        var manual = PracticeRevenueMaterializationService.evidenceForStoredAttributions(List.of(
                stored("MANUAL", "INVOICE", "INTERNAL")));
        assertEquals(PracticeRevenueAllocationService.SourceTier.PERSISTED, manual.tier());
        assertEquals(PracticeRevenueAllocationService.AttributionSource.PERSISTED_MANUAL, manual.source());
        assertEquals(PracticeRevenueAllocationService.AttributionStatus.CONFIRMED, manual.attributionStatus());

        var legacyAutomatic = PracticeRevenueMaterializationService.evidenceForStoredAttributions(List.of(
                stored("AUTO", "PHANTOM", "INTERNAL")));
        assertEquals(PracticeRevenueAllocationService.EvidenceState.ABSENT, legacyAutomatic.state());

        var automatic = PracticeRevenueMaterializationService.evidenceForStoredAttributions(List.of(
                versionedAuto("a-auto", "fingerprint-source")));
        assertEquals(PracticeRevenueAllocationService.SourceTier.PHANTOM_AUTO, automatic.tier());
        assertEquals(PracticeRevenueAllocationService.AttributionSource.PHANTOM_AUTO, automatic.source());
        assertEquals(PracticeRevenueAllocationService.AttributionStatus.ESTIMATED, automatic.attributionStatus());
    }

    @Test void mismatchedAutoPopulationProvenanceIsAbsentRatherThanReused(){
        var automatic = PracticeRevenueMaterializationService.evidenceForStoredAttributions(List.of(
                versionedAuto("a-1", "population-1"), versionedAuto("a-2", "population-2")));

        assertEquals(PracticeRevenueAllocationService.EvidenceState.ABSENT, automatic.state());
        assertEquals(PracticeRevenueAllocationService.SourceTier.PHANTOM_AUTO, automatic.tier());
    }

    @Test void unknownAutoAlgorithmOrSourceKindIsAbsentRatherThanReused(){
        var unknownAlgorithm = PracticeRevenueMaterializationService.evidenceForStoredAttributions(List.of(
                versionedAuto("algorithm", "population-1", "PRACTICE_AUTO_ATTRIBUTION_V2",
                        "REGISTERED_WORK_DISTRIBUTION")));
        var unknownSourceKind = PracticeRevenueMaterializationService.evidenceForStoredAttributions(List.of(
                versionedAuto("source-kind", "population-1", "PRACTICE_AUTO_ATTRIBUTION_V1",
                        "UNVERSIONED_WORK_DISTRIBUTION")));

        assertEquals(PracticeRevenueAllocationService.EvidenceState.ABSENT, unknownAlgorithm.state());
        assertEquals(PracticeRevenueAllocationService.EvidenceState.ABSENT, unknownSourceKind.state());
    }

    @Test void duplicateRiskUsesAnExplicitPubliclyCountableStatus(){
        assertEquals("PHANTOM_DUPLICATE_RISK", PracticeRevenueMaterializationService.duplicateRiskStatus(
                PracticeRevenueValuationService.ReasonCode.MANUAL_PHANTOM_DUPLICATE_RISK));
        assertEquals("NONE", PracticeRevenueMaterializationService.duplicateRiskStatus(
                PracticeRevenueValuationService.ReasonCode.GL_CONTROL_AMBIGUOUS));
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

    @Test void glReconciliationUsesOnlyTheGlControlledAllocationSubset(){
        var valuation = new PracticeRevenueValuationService();
        var allocation = new PracticeRevenueAllocationService();
        var glInput = new PracticeRevenueValuationService.DocumentInput("gl-document", "company",
                PracticeRevenueValuationService.DocumentType.INVOICE, "CREATED", false,
                LocalDate.parse("2026-02-17"), "DKK", "0",
                List.of(new PracticeRevenueValuationService.ItemInput("gl-item",
                        PracticeRevenueValuationService.ItemOrigin.BASE, "1", "100", "consultant", true,
                        null, null, null, false, null, null, null, null, null, null, null, null)),
                List.of(new PracticeRevenueValuationService.GlEntry("voucher", "company", 2025,
                        "BOOKED", 1, 0, "REVENUE", "-100", "gl-document")), List.of());
        var provisionalInput = new PracticeRevenueValuationService.DocumentInput(
                "provisional-document", "company", PracticeRevenueValuationService.DocumentType.INVOICE,
                "CREATED", false, LocalDate.parse("2026-02-17"), "DKK", "0",
                List.of(new PracticeRevenueValuationService.ItemInput("provisional-item",
                        PracticeRevenueValuationService.ItemOrigin.BASE, "1", "50", "consultant", true,
                        null, null, null, false, null, null, null, null, null, null, null, null)),
                List.of(), List.of());
        var documents = valuation.value(List.of(glInput, provisionalInput)).documents();
        var glDocument = documents.stream().filter(row -> row.documentUuid().equals("gl-document"))
                .findFirst().orElseThrow();
        var provisionalDocument = documents.stream()
                .filter(row -> row.documentUuid().equals("provisional-document")).findFirst().orElseThrow();
        var glAllocation = allocation.allocate(new PracticeRevenueAllocationService.AllocationRequest(
                glDocument.items().getFirst(), PracticeRevenueValuationService.DocumentType.INVOICE,
                List.of(sourceEvidence("gl", "PM", BigDecimal.ONE, false))));
        var provisionalAllocation = allocation.allocate(new PracticeRevenueAllocationService.AllocationRequest(
                provisionalDocument.items().getFirst(), PracticeRevenueValuationService.DocumentType.INVOICE,
                List.of(sourceEvidence("provisional", "DEV", BigDecimal.ONE, false))));
        var window = new PracticeRevenueMaterializationService.Window(false, "NO_WINDOW",
                null, null, null, null, null);

        var summary = new PracticeRevenueMaterializationService().summarize(2,
                List.of(new PracticeRevenueMaterializationService.ItemEnvelope(
                                "company", "CREATED", glDocument.items().getFirst(), glDocument),
                        new PracticeRevenueMaterializationService.ItemEnvelope(
                                "company", "CREATED", provisionalDocument.items().getFirst(), provisionalDocument)),
                List.of(new PracticeRevenueMaterializationService.AllocationEnvelope(
                                glDocument.items().getFirst().itemControlKey(), glAllocation),
                        new PracticeRevenueMaterializationService.AllocationEnvelope(
                                provisionalDocument.items().getFirst().itemControlKey(), provisionalAllocation)),
                List.of(), LocalDate.parse("2021-07-01"), LocalDate.parse("2026-06-30"),
                window, window);

        assertEquals(new BigDecimal("150.00"), summary.allocationTotal());
        assertEquals(new BigDecimal("100.00"), summary.glControlTotal());
        assertEquals(new BigDecimal("0.00"), summary.reconciliationGap());
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

    @Test void multiRowVoucherDependencySourceWithAGloballyUniqueControlIsValued(){
        var service=new PracticeRevenueMaterializationService();
        service.valuationService=new PracticeRevenueValuationService();
        // A voucher with multiple REVENUE rows (the formerly forced-fallback path that skipped the guard).
        var source=glBackedDocument("source","co",PracticeRevenueValuationService.DocumentType.INVOICE,
                "co:2025:BOOKED:42",42,List.of("-60","-40"));
        var unrelated=glBackedDocument("unrelated","co",PracticeRevenueValuationService.DocumentType.INVOICE,
                "co:2025:BOOKED:99",99,List.of("-100"));

        var valuation=service.valueDependencySource(source,List.of(source,unrelated));

        assertEquals(new BigDecimal("100.00"),valuation.items().getFirst().itemControlDkk());
        assertEquals(PracticeRevenueValuationService.ValuationStatus.CONFIRMED_GL,
                valuation.items().getFirst().valuationStatus());
    }

    @Test void multiRowVoucherDependencySourceCollidingWithAnotherDocumentFailsClosed(){
        var service=new PracticeRevenueMaterializationService();
        service.valuationService=new PracticeRevenueValuationService();
        var source=glBackedDocument("source","co",PracticeRevenueValuationService.DocumentType.INVOICE,
                "co:2025:BOOKED:42",42,List.of("-60","-40"));
        // Another eligible document resolves to the same inverse Booked voucher key.
        var colliding=glBackedDocument("colliding","co",PracticeRevenueValuationService.DocumentType.INVOICE,
                "co:2025:BOOKED:42",42,List.of("-100"));

        var valuation=service.valueDependencySource(source,List.of(source,colliding));

        assertNull(valuation.items().getFirst().itemControlDkk());
        assertEquals(PracticeRevenueValuationService.ValuationStatus.UNAVAILABLE_AMBIGUOUS,
                valuation.items().getFirst().valuationStatus());
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

    @Test void zeroItemInANonZeroDocumentPersistsWithAPositiveDocumentSign(){
        var valuation=new PracticeRevenueValuationService();
        var document=document(valuation,"doc",PracticeRevenueValuationService.DocumentType.INVOICE,"0","100");
        var rows=persistItems(document.items().stream()
                .map(item->new PracticeRevenueMaterializationService.ItemEnvelope(
                        "company","CREATED",item,document,null,null)).toList());

        assertTrue(rows.stream().allMatch(row->row.documentSign==(short)1));
        var zero=rows.stream().filter(row->row.sourceItemUuid.equals("item-0")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("0.00"),zero.itemControlDkk.setScale(2));
    }

    @Test void negativeInvoiceAdjustmentKeepsAPositiveDocumentSign(){
        var valuation=new PracticeRevenueValuationService();
        var document=document(valuation,"doc",PracticeRevenueValuationService.DocumentType.INVOICE,"-100");
        var row=persistItems(List.of(new PracticeRevenueMaterializationService.ItemEnvelope(
                "company","CREATED",document.items().getFirst(),document,null,null))).getFirst();

        assertEquals((short)1,row.documentSign);
        assertTrue(row.signedNativeControl.signum()<0);
    }

    @Test void creditReversalUsesNegativeDocumentSignRegardlessOfItemSign(){
        var valuation=new PracticeRevenueValuationService();
        var document=document(valuation,"doc",PracticeRevenueValuationService.DocumentType.CREDIT_NOTE,"-100");
        var row=persistItems(List.of(new PracticeRevenueMaterializationService.ItemEnvelope(
                "company","CREATED",document.items().getFirst(),document,null,null))).getFirst();

        assertEquals((short)-1,row.documentSign);
        assertTrue(row.signedNativeControl.signum()>0);
    }

    @Test void genuineAllZeroDocumentPersistsWithoutFailure(){
        var valuation=new PracticeRevenueValuationService();
        var document=document(valuation,"doc",PracticeRevenueValuationService.DocumentType.INVOICE,"0","0");
        var rows=assertDoesNotThrow(()->persistItems(document.items().stream()
                .map(item->new PracticeRevenueMaterializationService.ItemEnvelope(
                        "company","CREATED",item,document,null,null)).toList()));

        assertEquals(2,rows.size());
        assertTrue(rows.stream().allMatch(row->row.documentSign==(short)1));
        assertTrue(rows.stream().allMatch(row->row.itemControlDkk.signum()==0));
    }

    @Test void byteIdenticalCreditCopyEvidenceIsPersistedExactlyAsLoaded(){
        var row=persistCreditRow(copyProof("BYTE_IDENTICAL","SOURCE_ITEM",
                "1.000000000000000000","100.000000000000","fp-byte"));

        assertEquals("BYTE_IDENTICAL",row.creditCopyKind);
        assertEquals("SOURCE_ITEM",row.creditCopyScope);
        assertEquals(new BigDecimal("1.000000000000000000"),row.creditCopyScale);
        assertEquals(new BigDecimal("100.000000000000"),row.creditCopyOriginalSourceNativeAmount);
        assertEquals("fp-byte",row.creditCopyFingerprint);
    }

    @Test void scaledCreditCopyEvidenceIsPersistedExactlyAsLoaded(){
        var row=persistCreditRow(copyProof("SCALED","SOURCE_ITEM",
                "0.500000000000000000","100.000000000000","fp-scaled"));

        assertEquals("SCALED",row.creditCopyKind);
        assertEquals("SOURCE_ITEM",row.creditCopyScope);
        assertEquals(new BigDecimal("0.500000000000000000"),row.creditCopyScale);
        assertEquals(new BigDecimal("100.000000000000"),row.creditCopyOriginalSourceNativeAmount);
        assertEquals("fp-scaled",row.creditCopyFingerprint);
    }

    @Test void perItemResidualCreditCopyEvidenceIsPersistedExactlyAsLoaded(){
        var row=persistCreditRow(copyProof("RESIDUAL","SOURCE_ITEM",
                "0.250000000000000000","100.000000000000","fp-residual"));

        assertEquals("RESIDUAL",row.creditCopyKind);
        assertEquals("SOURCE_ITEM",row.creditCopyScope);
        assertEquals(new BigDecimal("0.250000000000000000"),row.creditCopyScale);
        assertEquals(new BigDecimal("100.000000000000"),row.creditCopyOriginalSourceNativeAmount);
        assertEquals("fp-residual",row.creditCopyFingerprint);
    }

    @Test void invoiceLevelResidualCreditCopyEvidenceIsPersistedWithNullScaleAndSourceItem(){
        var row=persistCreditRow(copyProof("RESIDUAL","SOURCE_INVOICE",
                null,"400.000000000000","fp-invoice-residual"));

        assertEquals("RESIDUAL",row.creditCopyKind);
        assertEquals("SOURCE_INVOICE",row.creditCopyScope);
        assertNull(row.creditCopyScale);
        assertEquals(new BigDecimal("400.000000000000"),row.creditCopyOriginalSourceNativeAmount);
        assertEquals("fp-invoice-residual",row.creditCopyFingerprint);
    }

    @Test void noneCreditCopyEvidenceStaysNoneWithNullSiblings(){
        var explicitNone=persistCreditRow(copyProof("NONE",null,null,null,null));
        var noProof=persistCreditRow(null);

        for(var row:List.of(explicitNone,noProof)){
            assertEquals("NONE",row.creditCopyKind);
            assertNull(row.creditCopyScope);
            assertNull(row.creditCopyScale);
            assertNull(row.creditCopyOriginalSourceNativeAmount);
            assertNull(row.creditCopyFingerprint);
        }
    }

    @Test void zeroSourceByteIdenticalCreditCopyEvidencePersistsItsZeroOriginalAmount(){
        var row=persistCreditRow(copyProof("BYTE_IDENTICAL","SOURCE_ITEM",
                "1.000000000000000000","0.000000000000","fp-zero-source"));

        assertEquals("BYTE_IDENTICAL",row.creditCopyKind);
        assertEquals(new BigDecimal("0.000000000000"),row.creditCopyOriginalSourceNativeAmount);
    }

    @Test void creditCopyEvidenceIsPersistedVerbatimWithoutRevalidatingItsFingerprint(){
        // Persist records the loaded proof exactly; it never recomputes or drops a stale fingerprint.
        var row=persistCreditRow(copyProof("SCALED","SOURCE_ITEM",
                "0.500000000000000000","100.000000000000","stale-but-persisted"));

        assertEquals("stale-but-persisted",row.creditCopyFingerprint);
    }

    private static List<PracticeRevenueItem> persistItems(
            List<PracticeRevenueMaterializationService.ItemEnvelope> envelopes){
        var service=new PracticeRevenueMaterializationService();
        service.em=mock(EntityManager.class);
        var window=new PracticeRevenueMaterializationService.Window(false,"NO_WINDOW",null,null,null,null,null);
        var attempt=new PracticeRevenueMaterializationService.Attempt("gen","owner",BigInteger.ZERO,
                LocalDateTime.parse("2026-07-15T00:00:00"),"basis",BigInteger.ZERO,"vector",
                BigInteger.ZERO,BigInteger.ZERO,Map.of());
        var candidate=new PracticeRevenueMaterializationService.BuildCandidate(
                LocalDateTime.parse("2026-07-15T00:00:00"),LocalDate.parse("2021-07-01"),
                LocalDate.parse("2026-06-01"),window,window,envelopes.size(),envelopes,List.of(),List.of(),
                0,0,0,0,0,0,0,0,BigDecimal.ZERO.setScale(2),BigDecimal.ZERO.setScale(2),null,null);
        service.persist(attempt,candidate);
        var captor=ArgumentCaptor.forClass(PracticeRevenueItem.class);
        verify(service.em,atLeastOnce()).persist(captor.capture());
        return captor.getAllValues();
    }

    private static PracticeRevenueItem persistCreditRow(
            PracticeRevenueMaterializationService.CreditEvidence proof){
        var valuation=new PracticeRevenueValuationService();
        var document=document(valuation,"credit-document",
                PracticeRevenueValuationService.DocumentType.CREDIT_NOTE,"100");
        return persistItems(List.of(new PracticeRevenueMaterializationService.ItemEnvelope(
                "company","CREATED",document.items().getFirst(),document,null,proof))).getFirst();
    }

    private static PracticeRevenueValuationService.DocumentValuation document(
            PracticeRevenueValuationService valuation,String documentUuid,
            PracticeRevenueValuationService.DocumentType type,String... rates){
        var items=new java.util.ArrayList<PracticeRevenueValuationService.ItemInput>();
        int index=0;
        for(String rate:rates){
            items.add(new PracticeRevenueValuationService.ItemInput("item-"+(index++),
                    PracticeRevenueValuationService.ItemOrigin.BASE,"1.000000",rate,"consultant",true,
                    null,null,null,false,null,null,null,null,null,null,null,null));
        }
        var input=new PracticeRevenueValuationService.DocumentInput(documentUuid,"company",type,"CREATED",
                false,LocalDate.parse("2026-02-17"),"DKK","0",items,List.of(),List.of());
        return valuation.value(List.of(input)).documents().getFirst();
    }

    private static PracticeRevenueMaterializationService.CreditEvidence copyProof(
            String kind,String scope,String scale,String original,String fingerprint){
        return new PracticeRevenueMaterializationService.CreditEvidence("credit-item",
                "SOURCE_INVOICE".equals(scope)?null:"source-item",kind,scope,
                scale==null?null:new BigDecimal(scale),original==null?null:new BigDecimal(original),
                fingerprint,"credit-document","source-document",null,null,"BASE",
                "consultant",null,null,null,null);
    }

    private static PracticeRevenueMaterializationService.StoredAttribution stored(
            String source, String documentType, String consultantType) {
        return new PracticeRevenueMaterializationService.StoredAttribution("a-" + source,
                "consultant", new BigDecimal("100.000000"), source, "PM", consultantType,
                "HISTORICAL", documentType, null, null, null);
    }

    private static PracticeRevenueMaterializationService.StoredAttribution versionedAuto(
            String uuid, String population) {
        return versionedAuto(uuid, population, "PRACTICE_AUTO_ATTRIBUTION_V1",
                "REGISTERED_WORK_DISTRIBUTION");
    }

    private static PracticeRevenueMaterializationService.StoredAttribution versionedAuto(
            String uuid, String population, String algorithmVersion, String sourceKind) {
        return new PracticeRevenueMaterializationService.StoredAttribution(uuid,
                "consultant", new BigDecimal("50.000000"), "AUTO", "PM", "INTERNAL",
                "HISTORICAL", "PHANTOM", algorithmVersion, sourceKind,
                (population.endsWith("1") ? "a" : "b").repeat(64));
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

    private static PracticeRevenueValuationService.DocumentInput glBackedDocument(
            String uuid,String company,PracticeRevenueValuationService.DocumentType type,
            String voucherKey,long voucherNumber,List<String> amounts){
        var item=new PracticeRevenueValuationService.ItemInput("item-"+uuid,
                PracticeRevenueValuationService.ItemOrigin.BASE,"1.000000","100.000000",
                "consultant",true,null,null,null,false,null,null,null,null,null,null,null,null);
        var gl=new java.util.ArrayList<PracticeRevenueValuationService.GlEntry>();
        for(String amount:amounts){
            gl.add(new PracticeRevenueValuationService.GlEntry(voucherKey,company,2025,"BOOKED",
                    voucherNumber,0,"REVENUE",amount,"voucher-"+voucherNumber));
        }
        return new PracticeRevenueValuationService.DocumentInput(uuid,company,type,"CREATED",false,
                LocalDate.parse("2026-02-17"),"DKK","0",List.of(item),gl,List.of());
    }

    private static PracticeRevenueMaterializationService.StoredSelfBilledAssignment selfBilled(
            String uuid,String consultant,int year,int month,String share,String practice,String source){
        return new PracticeRevenueMaterializationService.StoredSelfBilledAssignment("phantom-item",uuid,
                consultant,year,month,new BigDecimal(share),source,practice,"INTERNAL","HISTORY",
                new BigDecimal("100.000000000000"),new BigDecimal("-100.000000000000"));
    }
    @Test void coverageMissWhenAConsumedDeliveryDateFallsBeforeCertifiedBasisCoverage(){
        var service=new PracticeRevenueMaterializationService();
        EntityManager em=mock(EntityManager.class);
        service.em=em; service.queryTimeout=java.time.Duration.ofMinutes(2);
        Query bounds=mock(Query.class);
        when(em.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(bounds);
        when(bounds.setHint(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(bounds);
        when(bounds.setParameter(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(bounds);
        when(bounds.getSingleResult()).thenReturn(new Object[]{
                java.sql.Date.valueOf("2021-07-01"),java.sql.Date.valueOf("2026-06-30"),"f".repeat(64)});

        var miss=assertThrows(
                PracticeRevenueMaterializationService.RevenueBasisCoverageMissException.class,
                ()->service.assertConsumedDependenciesCovered(
                        attempt("basis-1"),candidateWithDependencies(
                                List.of(deliveryDependency("2020-01-15")))));
        assertEquals(LocalDate.parse("2020-01-15"),miss.affectedStart());
    }

    @Test void coveredWhenEveryConsumedDateIsWithinTheCertifiedBasisCoverage(){
        var service=new PracticeRevenueMaterializationService();
        EntityManager em=mock(EntityManager.class);
        service.em=em; service.queryTimeout=java.time.Duration.ofMinutes(2);
        Query bounds=mock(Query.class);
        when(em.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(bounds);
        when(bounds.setHint(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(bounds);
        when(bounds.setParameter(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(bounds);
        when(bounds.getSingleResult()).thenReturn(new Object[]{
                java.sql.Date.valueOf("2021-07-01"),java.sql.Date.valueOf("2026-06-30"),"f".repeat(64)});

        assertDoesNotThrow(()->service.assertConsumedDependenciesCovered(
                attempt("basis-1"),candidateWithDependencies(
                        List.of(deliveryDependency("2022-01-15")))));
    }

    @Test void capacityEnvelopeEndingExactlyAtCoveragePlusOneDayIsCoveredNotAMiss(){
        // C1a: PracticeBasisMaterializationService clamps open capacity intervals to coverageEnd+1
        // (2026-07-01). Treating that exclusive end inclusively would flag every active consultant.
        assertDoesNotThrow(()->coverageService().assertConsumedDependenciesCovered(
                attempt("basis-1"),candidateWithDependencies(List.of(intervalDependency(
                        "2026-06-01","2026-06-01","2026-06-15","2026-06-01","2026-07-01")))));
    }
    @Test void deliveryEndExactlyAtCoveragePlusOneDayIsCoveredNotAMiss(){
        assertDoesNotThrow(()->coverageService().assertConsumedDependenciesCovered(
                attempt("basis-1"),candidateWithDependencies(List.of(intervalDependency(
                        "2026-06-01","2026-06-15","2026-07-01",null,null)))));
    }
    @Test void deliveryEndTwoDaysAfterCoverageIsARealMiss(){
        var miss=assertThrows(PracticeRevenueMaterializationService.RevenueBasisCoverageMissException.class,
                ()->coverageService().assertConsumedDependenciesCovered(
                        attempt("basis-1"),candidateWithDependencies(List.of(intervalDependency(
                                "2026-06-01","2026-06-15","2026-07-02",null,null)))));
        assertEquals(LocalDate.parse("2026-07-02"),miss.affectedStart());
    }
    @Test void deliveryStartBeforeCoverageStartStillMisses(){
        var miss=assertThrows(PracticeRevenueMaterializationService.RevenueBasisCoverageMissException.class,
                ()->coverageService().assertConsumedDependenciesCovered(
                        attempt("basis-1"),candidateWithDependencies(List.of(intervalDependency(
                                "2026-06-01","2021-06-30","2026-06-30",null,null)))));
        assertEquals(LocalDate.parse("2021-06-30"),miss.affectedStart());
    }

    private static PracticeRevenueMaterializationService coverageService(){
        var service=new PracticeRevenueMaterializationService();
        EntityManager em=mock(EntityManager.class);
        service.em=em; service.queryTimeout=java.time.Duration.ofMinutes(2);
        Query bounds=mock(Query.class);
        when(em.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(bounds);
        when(bounds.setHint(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(bounds);
        when(bounds.setParameter(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(bounds);
        when(bounds.getSingleResult()).thenReturn(new Object[]{
                java.sql.Date.valueOf("2021-07-01"),java.sql.Date.valueOf("2026-06-30"),"f".repeat(64)});
        return service;
    }
    private static PracticeRevenueMaterializationService.DependencyEnvelope intervalDependency(
            String recognizedMonth,String deliveryStart,String deliveryEndExclusive,
            String capacityStart,String capacityEndExclusive){
        return new PracticeRevenueMaterializationService.DependencyEnvelope(
                "item-1","REGISTERED_WORK_DELIVERY","key-1",LocalDate.parse(recognizedMonth),
                "DELIVERY_EVIDENCE","doc-1","srcitem-1",null,"work-1","user-1",null,null,null,null,null,
                null,null,null,
                capacityStart==null?null:LocalDate.parse(capacityStart),
                capacityEndExclusive==null?null:LocalDate.parse(capacityEndExclusive),
                deliveryStart==null?null:LocalDate.parse(deliveryStart),
                deliveryEndExclusive==null?null:LocalDate.parse(deliveryEndExclusive),
                null,"fp");
    }
    private static PracticeRevenueMaterializationService.Attempt attempt(String basisGenerationId){
        return new PracticeRevenueMaterializationService.Attempt("gen-1","owner-1",BigInteger.ONE,
                LocalDateTime.parse("2026-07-15T00:00:00"),basisGenerationId,BigInteger.TEN,"v".repeat(64),
                BigInteger.ONE,BigInteger.ZERO,Map.of());
    }
    private static PracticeRevenueMaterializationService.DependencyEnvelope deliveryDependency(String deliveryDate){
        return new PracticeRevenueMaterializationService.DependencyEnvelope(
                "item-1","REGISTERED_WORK_DELIVERY","key-1",LocalDate.parse("2026-06-01"),
                "DELIVERY_EVIDENCE","doc-1","srcitem-1",null,"work-1","user-1",null,null,null,null,null,
                null,null,null,null,null,LocalDate.parse(deliveryDate),LocalDate.parse(deliveryDate),
                null,"fp");
    }
    private static PracticeRevenueMaterializationService.BuildCandidate candidateWithDependencies(
            List<PracticeRevenueMaterializationService.DependencyEnvelope> dependencies){
        var window=new PracticeRevenueMaterializationService.Window(false,"NO_WINDOW",null,null,null,null,null);
        return new PracticeRevenueMaterializationService.BuildCandidate(LocalDateTime.parse("2026-07-15T00:00:00"),
                LocalDate.parse("2021-07-01"),LocalDate.parse("2026-06-01"),window,window,0,
                List.of(),List.of(),dependencies,0,0,0,0,0,0,0,0,
                new BigDecimal("0.00"),new BigDecimal("0.00"),null,null);
    }
    private static PracticeRevenueMaterializationService.BuildCandidate candidate(String item,String allocation){
        var window=new PracticeRevenueMaterializationService.Window(false,"NO_WINDOW",null,null,null,null,null);
        return new PracticeRevenueMaterializationService.BuildCandidate(LocalDateTime.parse("2026-07-15T00:00:00"),
                LocalDate.parse("2021-07-01"),LocalDate.parse("2026-06-01"),window,window,0,
                List.of(),List.of(),List.of(),0,0,0,0,0,0,0,0,
                new BigDecimal(item),new BigDecimal(allocation),null,null);
    }
    private static PracticeRevenueMaterializationService.BuildCandidate glCandidate(String item,String allocation,
                                                                                    String glControl,String gap){
        var window=new PracticeRevenueMaterializationService.Window(false,"NO_WINDOW",null,null,null,null,null);
        return new PracticeRevenueMaterializationService.BuildCandidate(LocalDateTime.parse("2026-07-15T00:00:00"),
                LocalDate.parse("2021-07-01"),LocalDate.parse("2026-06-01"),window,window,0,
                List.of(),List.of(),List.of(),0,0,0,0,0,0,0,0,
                new BigDecimal(item),new BigDecimal(allocation),new BigDecimal(glControl),new BigDecimal(gap));
    }

    // economics_accounting_year is VARCHAR(20) in V338 with the documented "2025/2026" format; the
    // JDBC driver returns a String, never a Number. Parsing must not crash the refresh: the leading
    // fiscal-start year is the identifier, blank/null is absent (0), and a present-but-unparseable
    // value is invalid (-1) so the cross-year guard fails closed to AMBIGUOUS instead of matching.
    @Test void economicAccountingYearStringParsesToItsLeadingFiscalStartYear(){
        assertEquals(2025,PracticeRevenueMaterializationService.accountingYearStart("2025/2026"));
        assertEquals(2025,PracticeRevenueMaterializationService.accountingYearStart("2025"));
        assertEquals(2025,PracticeRevenueMaterializationService.accountingYearStart(" 2025/2026 "));
    }
    @Test void economicAccountingYearAbsentValuesAreZero(){
        assertEquals(0,PracticeRevenueMaterializationService.accountingYearStart(null));
        assertEquals(0,PracticeRevenueMaterializationService.accountingYearStart(""));
        assertEquals(0,PracticeRevenueMaterializationService.accountingYearStart("   "));
        assertEquals(0,PracticeRevenueMaterializationService.accountingYearStart(0));
        assertEquals(0,PracticeRevenueMaterializationService.accountingYearStart(-3));
    }
    @Test void economicAccountingYearNumericValuesStillParse(){
        assertEquals(2025,PracticeRevenueMaterializationService.accountingYearStart(2025));
        assertEquals(2025,PracticeRevenueMaterializationService.accountingYearStart(java.math.BigInteger.valueOf(2025)));
    }
    /**
     * DeliveryEvidenceBundle wraps its maps in Map.copyOf, whose get(null) throws NPE. Sentinel
     * rows (zero-item documents, generated residuals) carry a null source-item UUID and must simply
     * resolve to no delivery evidence (observed on staging: first revenue build crashed
     * MATERIALIZATION_FAILED on ImmutableCollections.MapN.get(null)).
     */
    @Test void deliveryEvidenceLookupWithANullSentinelKeyResolvesToNoEvidenceNotACrash(){
        var bundle=new PracticeRevenueMaterializationService.DeliveryEvidenceBundle(
                java.util.Map.of(),java.util.Map.of());
        assertNull(bundle.evidenceFor(null));
        assertNull(bundle.evidenceFor("missing"));
        assertEquals(java.util.List.of(),bundle.dependenciesFor(null));
        assertEquals(java.util.List.of(),bundle.dependenciesFor("missing"));
    }
    /**
     * The atomic build+persist transaction must outlive Narayana's 600s default (a staging revenue
     * build was reaper-aborted at 600s and surfaced ~953s in). The clamp keeps a misconfigured
     * Duration from passing a non-positive timeout or disabling the reaper outright.
     */
    @Test void buildTransactionTimeoutClampsToWholeSecondsWithinSaneBounds(){
        assertEquals(3600,PracticeRevenueMaterializationService.transactionTimeoutSeconds(java.time.Duration.ofHours(1)));
        assertEquals(1,PracticeRevenueMaterializationService.transactionTimeoutSeconds(java.time.Duration.ZERO));
        assertEquals(86_400,PracticeRevenueMaterializationService.transactionTimeoutSeconds(java.time.Duration.ofDays(30)));
    }

    @Test void economicAccountingYearUnparseablePresentValueIsInvalidNotAbsent(){
        assertEquals(-1,PracticeRevenueMaterializationService.accountingYearStart("not-a-year"));
        assertEquals(-1,PracticeRevenueMaterializationService.accountingYearStart("25/26"));
    }
}
