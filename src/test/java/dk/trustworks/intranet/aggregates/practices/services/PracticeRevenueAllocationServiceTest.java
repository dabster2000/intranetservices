package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.invoice.services.RegisteredDeliveryEvidenceResolver;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueAllocationService.*;
import static dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueValuationService.ControlSource;
import static dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueValuationService.DocumentType;
import static dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueValuationService.ItemCategory;
import static dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueValuationService.ItemControl;
import static dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueValuationService.ItemRowKind;
import static dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueValuationService.ValuationStatus;
import static org.junit.jupiter.api.Assertions.*;

class PracticeRevenueAllocationServiceTest {

    private final PracticeRevenueAllocationService service = new PracticeRevenueAllocationService();

    @Test
    void partialPersistedEvidenceClosesToUnassignedAndStopsBeforeDirectFallback() {
        SourceEvidence persisted = source(SourceTier.PERSISTED, AttributionSource.PERSISTED_MANUAL,
                AttributionStatus.CONFIRMED, true, candidate("p", "u1", "PM", "0.600000"));
        SourceEvidence direct = source(SourceTier.LEGACY_DIRECT, AttributionSource.LEGACY_DIRECT_FALLBACK,
                AttributionStatus.ESTIMATED, false, candidate("d", "u2", "BA", "1"));

        AllocationResult result = service.allocate(new AllocationRequest(
                item("100.00", DocumentType.INVOICE, ItemCategory.DELIVERY_BASE, false),
                DocumentType.INVOICE, List.of(persisted, direct)));

        assertEquals(AttributionSource.PERSISTED_MANUAL, result.source());
        assertEquals(2, result.allocations().size());
        assertEquals(new BigDecimal("100.00"), result.allocatedControlDkk());
        assertEquals(new BigDecimal("60.00"), amount(result, SegmentId.PM));
        assertEquals(new BigDecimal("40.00"), amount(result, SegmentId.UNASSIGNED));
        assertTrue(result.allocations().stream().noneMatch(a -> "u2".equals(a.consultantUuid())));
        assertEquals(ReasonCode.PARTIAL_SOURCE_RESIDUAL, result.reason());
    }

    @Test
    void identifiedInvalidTierStopsInsteadOfFallingThrough() {
        SourceEvidence invalid = SourceEvidence.invalid(SourceTier.PERSISTED,
                AttributionSource.PERSISTED_MANUAL, ReasonCode.CONTRADICTORY_EVIDENCE);
        SourceEvidence direct = source(SourceTier.LEGACY_DIRECT, AttributionSource.LEGACY_DIRECT_FALLBACK,
                AttributionStatus.ESTIMATED, false, candidate("d", "u", "PM", "1"));

        AllocationResult result = service.allocate(new AllocationRequest(
                item("25.00", DocumentType.INVOICE, ItemCategory.DELIVERY_BASE, false),
                DocumentType.INVOICE, List.of(invalid, direct)));

        assertEquals(AttributionSource.UNASSIGNED, result.source());
        assertEquals(ReasonCode.CONTRADICTORY_EVIDENCE, result.reason());
        assertEquals(new BigDecimal("25.00"), result.allocations().getFirst().allocationDkk());
    }

    @Test
    void absentTierFallsThroughToDisclosedLegacyEstimate() {
        SourceEvidence direct = source(SourceTier.LEGACY_DIRECT, AttributionSource.LEGACY_DIRECT_FALLBACK,
                AttributionStatus.ESTIMATED, false, candidate("d", "u", "CYB", "1"));

        AllocationResult result = service.allocate(new AllocationRequest(
                item("25.00", DocumentType.INVOICE, ItemCategory.DELIVERY_BASE, false),
                DocumentType.INVOICE, List.of(SourceEvidence.absent(SourceTier.PERSISTED), direct)));

        assertEquals(AttributionSource.LEGACY_DIRECT_FALLBACK, result.source());
        assertEquals(AttributionStatus.ESTIMATED, result.status());
        assertEquals(SegmentId.CYB, result.allocations().getFirst().segmentId());
    }

    @Test
    void unvaluedItemResolvesEvidenceScopeWithoutAllocatingMoney() {
        SourceEvidence human = source(SourceTier.HUMAN, AttributionSource.HUMAN,
                AttributionStatus.CONFIRMED, false,
                candidate("human", "consultant", "PM", "1"));
        AllocationRequest request = new AllocationRequest(
                unvaluedItem(ItemRowKind.SOURCE_ITEM), DocumentType.INVOICE, List.of(human));

        EvidenceScope scope = service.resolveEvidenceScope(request);
        AllocationResult allocation = service.allocate(request);

        assertEquals(ScopeResolutionStatus.RESOLVED, scope.status());
        assertEquals(SegmentId.PM, scope.resolvedSegment());
        assertEquals("HISTORY", scope.practiceBasis());
        assertEquals("INTERNAL", scope.consultantTypeBasis());
        assertTrue(allocation.allocations().isEmpty());
        assertEquals(BigDecimal.ZERO.setScale(2), allocation.allocatedControlDkk());
    }

    @Test
    void zeroItemSentinelUsesTheSameEvidenceOnlyPrecedenceAndFailsClosedOnAmbiguity() {
        SourceEvidence registered = source(SourceTier.REGISTERED_VALUE,
                AttributionSource.REGISTERED_VALUE, AttributionStatus.ESTIMATED, false,
                candidate("registered", "consultant", "BA", "1"));
        EvidenceScope resolved = service.resolveEvidenceScope(new AllocationRequest(
                unvaluedItem(ItemRowKind.DOCUMENT_EVIDENCE), DocumentType.INVOICE,
                List.of(registered)));
        EvidenceScope ambiguous = service.resolveEvidenceScope(new AllocationRequest(
                unvaluedItem(ItemRowKind.DOCUMENT_EVIDENCE), DocumentType.INVOICE,
                List.of(source(SourceTier.HUMAN, AttributionSource.HUMAN,
                        AttributionStatus.CONFIRMED, false,
                        candidate("pm", "pm-user", "PM", "0.5"),
                        candidate("ba", "ba-user", "BA", "0.5")))));

        assertEquals(ScopeResolutionStatus.RESOLVED, resolved.status());
        assertEquals(SegmentId.BA, resolved.resolvedSegment());
        assertEquals(ScopeResolutionStatus.AMBIGUOUS, ambiguous.status());
        assertNull(ambiguous.resolvedSegment());
    }

    @Test
    void commercialAdjustmentUsesBaseDistributionNotDirectConsultant() {
        SourceEvidence base = source(SourceTier.BASE_DISTRIBUTION, AttributionSource.BASE_DISTRIBUTION,
                AttributionStatus.CONFIRMED, false,
                candidate("a", "u1", "PM", "0.1"), candidate("b", "u2", "BA", "0.9"));
        SourceEvidence direct = source(SourceTier.LEGACY_DIRECT, AttributionSource.LEGACY_DIRECT_FALLBACK,
                AttributionStatus.ESTIMATED, false, candidate("d", "u3", "DEV", "1"));

        AllocationResult result = service.allocate(new AllocationRequest(
                item("-10.00", DocumentType.INVOICE, ItemCategory.COMMERCIAL_ADJUSTMENT, false),
                DocumentType.INVOICE, List.of(base, direct)));

        assertEquals(new BigDecimal("-1.00"), amount(result, SegmentId.PM));
        assertEquals(new BigDecimal("-9.00"), amount(result, SegmentId.BA));
        assertTrue(result.allocations().stream().noneMatch(a -> "u3".equals(a.consultantUuid())));
    }

    @Test
    void creditUsesSourceSharesAndScalesCurrentSignedControl() {
        SourceEvidence copy = source(SourceTier.CREDIT_COPY, AttributionSource.CREDIT_COPY,
                AttributionStatus.CONFIRMED, false,
                candidate("a", "u1", "PM", "0.25"), candidate("b", "u2", "SA", "0.75"));

        AllocationResult result = service.allocate(new AllocationRequest(
                item("-80.00", DocumentType.CREDIT_NOTE, ItemCategory.DELIVERY_BASE, false),
                DocumentType.CREDIT_NOTE, List.of(copy)));

        assertEquals(new BigDecimal("-20.00"), amount(result, SegmentId.PM));
        assertEquals(new BigDecimal("-60.00"), amount(result, SegmentId.SA));
        assertEquals(new BigDecimal("-80.00"), result.allocatedControlDkk());
    }

    @Test
    void externalTypeWinsOverCorePracticeAndMissingTypeDoesNotDefault() {
        RecipientCandidate external = new RecipientCandidate("e", "ext", "PM", ConsultantType.EXTERNAL,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), "MANUAL", BigDecimal.ONE,
                PracticeResolutionMethod.DATED_DELIVERY, false, false, null, null, null, false);
        AllocationResult valid = service.allocate(new AllocationRequest(
                item("10.00", DocumentType.INVOICE, ItemCategory.DELIVERY_BASE, false),
                DocumentType.INVOICE, List.of(source(SourceTier.PERSISTED,
                AttributionSource.PERSISTED_MANUAL, AttributionStatus.CONFIRMED, false, external))));
        RecipientCandidate missingType = new RecipientCandidate("m", "m", "PM", null,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), "MANUAL", BigDecimal.ONE,
                PracticeResolutionMethod.DATED_DELIVERY, false, false, null, null, null, false);
        AllocationResult invalid = service.allocate(new AllocationRequest(
                item("10.00", DocumentType.INVOICE, ItemCategory.DELIVERY_BASE, false),
                DocumentType.INVOICE, List.of(source(SourceTier.PERSISTED,
                AttributionSource.PERSISTED_MANUAL, AttributionStatus.CONFIRMED, false, missingType))));

        assertEquals(SegmentId.EXTERNAL, valid.allocations().getFirst().segmentId());
        assertEquals(SegmentId.UNASSIGNED, invalid.allocations().getFirst().segmentId());
        assertEquals(ReasonCode.MISSING_CONSULTANT_TYPE, invalid.reason());
    }

    @Test
    void duplicateSourceUuidInvalidatesEvidenceBeforeCanonicalMerging() {
        RecipientCandidate first = candidate("same", "u", "PM", "0.5");
        RecipientCandidate second = candidate("same", "u", "PM", "0.5");

        AllocationResult result = service.allocate(new AllocationRequest(
                item("10.00", DocumentType.INVOICE, ItemCategory.DELIVERY_BASE, false),
                DocumentType.INVOICE, List.of(source(SourceTier.PERSISTED,
                AttributionSource.PERSISTED_MANUAL, AttributionStatus.CONFIRMED, false, first, second))));

        assertEquals(ReasonCode.DUPLICATE_SOURCE_RECORD, result.reason());
        assertEquals(SegmentId.UNASSIGNED, result.allocations().getFirst().segmentId());
    }

    @Test
    void hundredWayPositiveAndNegativeSubcentVectorsConserveWithoutSignReversal() {
        List<RecipientCandidate> recipients = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            recipients.add(candidate("s%03d".formatted(index), "u%03d".formatted(index), "PM", "0.01"));
        }
        SourceEvidence source = new SourceEvidence(SourceTier.PERSISTED, EvidenceState.PRESENT,
                AttributionSource.PERSISTED_MANUAL, AttributionStatus.CONFIRMED, false,
                recipients, ReasonCode.NONE);

        AllocationResult positive = service.allocate(new AllocationRequest(
                item("0.60", DocumentType.INVOICE, ItemCategory.DELIVERY_BASE, false),
                DocumentType.INVOICE, List.of(source)));
        AllocationResult negative = service.allocate(new AllocationRequest(
                item("-0.60", DocumentType.INVOICE, ItemCategory.DELIVERY_BASE, false),
                DocumentType.INVOICE, List.of(source)));

        assertEquals(new BigDecimal("0.60"), positive.allocatedControlDkk());
        assertEquals(60, positive.allocations().stream().filter(a -> a.allocationDkk().equals(new BigDecimal("0.01"))).count());
        assertTrue(positive.allocations().stream().noneMatch(a -> a.allocationDkk().signum() < 0));
        assertEquals(new BigDecimal("-0.60"), negative.allocatedControlDkk());
        assertEquals(60, negative.allocations().stream().filter(a -> a.allocationDkk().equals(new BigDecimal("-0.01"))).count());
        assertTrue(negative.allocations().stream().noneMatch(a -> a.allocationDkk().signum() > 0));
    }

    @Test
    void registeredDeliveryNetsCorrectionsBeforeWeightingAndUsesHoursOnlyForResolvedZeroRates() {
        LocalDate day = LocalDate.of(2026, 1, 3);
        List<RegisteredDeliveryEvidenceResolver.ResolvedDelivery> valueRows = List.of(
                delivery("w1", "u1", day, "10", "10", "100", RegisteredDeliveryEvidenceResolver.RateResolutionStatus.RESOLVED),
                delivery("w2", "u1", day, "-5", "10", "-50", RegisteredDeliveryEvidenceResolver.RateResolutionStatus.RESOLVED),
                delivery("w3", "u2", day, "5", "10", "50", RegisteredDeliveryEvidenceResolver.RateResolutionStatus.RESOLVED));

        SourceEvidence values = service.registeredDeliveryEvidence(valueRows,
                row -> new RecipientIdentity(row.effectiveConsultantUuid(), "PM", ConsultantType.INTERNAL,
                        PracticeResolutionMethod.DATED_DELIVERY, false));
        assertEquals(SourceTier.REGISTERED_VALUE, values.tier());
        assertEquals(new BigDecimal("0.500000000000000000"), values.candidates().getFirst().rawFraction());

        List<RegisteredDeliveryEvidenceResolver.ResolvedDelivery> zeroRate = List.of(
                delivery("z1", "u1", day, "3", "0", "0", RegisteredDeliveryEvidenceResolver.RateResolutionStatus.RESOLVED),
                delivery("z2", "u2", day, "1", "0", "0", RegisteredDeliveryEvidenceResolver.RateResolutionStatus.RESOLVED));
        SourceEvidence hours = service.registeredDeliveryEvidence(zeroRate,
                row -> new RecipientIdentity(row.effectiveConsultantUuid(), "PM", ConsultantType.INTERNAL,
                        PracticeResolutionMethod.DATED_DELIVERY, false));
        assertEquals(SourceTier.REGISTERED_HOURS, hours.tier());
        assertEquals(new BigDecimal("0.750000000000000000"), hours.candidates().getFirst().rawFraction());
    }

    @Test
    void invalidRegisteredRateCannotMasqueradeAsZeroHourFallback() {
        SourceEvidence evidence = service.registeredDeliveryEvidence(List.of(
                delivery("w", "u", LocalDate.of(2026, 1, 1), "5", null, null,
                        RegisteredDeliveryEvidenceResolver.RateResolutionStatus.MISSING)),
                row -> new RecipientIdentity("u", "PM", ConsultantType.INTERNAL,
                        PracticeResolutionMethod.DATED_DELIVERY, false));

        assertEquals(EvidenceState.INVALID, evidence.state());
        assertEquals(ReasonCode.DELIVERY_EVIDENCE_AMBIGUOUS, evidence.reason());
    }

    @Test
    void coverageUsesAbsoluteMovementAndLimitsPartialAffectedToUnassigned() {
        SourceEvidence partial = source(SourceTier.PERSISTED, AttributionSource.PERSISTED_MANUAL,
                AttributionStatus.CONFIRMED, true, candidate("p", "u", "PM", "0.75"));
        AllocationResult result = service.allocate(new AllocationRequest(
                item("-100.00", DocumentType.INVOICE, ItemCategory.DELIVERY_BASE, false),
                DocumentType.INVOICE, List.of(partial)));

        Coverage coverage = PracticeRevenueAllocationService.coverage(List.of(result));

        assertEquals(new BigDecimal("100.00"), coverage.usableAbsoluteMovementDkk());
        assertEquals(new BigDecimal("75.00"), coverage.confirmedAttributedAbsoluteMovementDkk());
        assertEquals(new BigDecimal("25.00"), coverage.partialAttributionAffectedRevenueDkk());
    }

    private static BigDecimal amount(AllocationResult result, SegmentId segment) {
        return result.allocations().stream().filter(a -> a.segmentId() == segment)
                .map(Allocation::allocationDkk).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private static SourceEvidence source(SourceTier tier, AttributionSource source, AttributionStatus status,
                                         boolean residual, RecipientCandidate... candidates) {
        return new SourceEvidence(tier, EvidenceState.PRESENT, source, status, residual,
                List.of(candidates), ReasonCode.NONE);
    }

    private static RecipientCandidate candidate(String id, String consultant, String practice, String fraction) {
        return new RecipientCandidate(id, consultant, practice, ConsultantType.INTERNAL,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), "TEST",
                new BigDecimal(fraction), PracticeResolutionMethod.DATED_DELIVERY,
                false, false, null, null, null, false);
    }

    private static ItemControl item(String control, DocumentType type, ItemCategory category, boolean residual) {
        BigDecimal amount = new BigDecimal(control);
        return new ItemControl("ITEM:d:i", "i", "d", LocalDate.of(2026, 2, 1),
                residual ? ItemRowKind.DOCUMENT_RESIDUAL : ItemRowKind.SOURCE_ITEM,
                category, null, "DKK", amount.setScale(12), amount.setScale(12),
                amount, amount, ControlSource.ECONOMIC_GL, ValuationStatus.CONFIRMED_GL,
                amount.setScale(4), BigDecimal.ZERO.setScale(4), BigDecimal.ONE.setScale(18),
                true, amount, amount, BigDecimal.ZERO, false, null, false, residual,
                PracticeRevenueValuationService.ReasonCode.NONE);
    }

    private static ItemControl unvaluedItem(ItemRowKind rowKind) {
        return new ItemControl(rowKind + ":d:i",
                rowKind == ItemRowKind.DOCUMENT_EVIDENCE ? null : "i", "d",
                LocalDate.of(2026, 2, 1), rowKind,
                rowKind == ItemRowKind.DOCUMENT_EVIDENCE ? null : ItemCategory.DELIVERY_BASE,
                null, rowKind == ItemRowKind.DOCUMENT_EVIDENCE ? null : "EUR",
                rowKind == ItemRowKind.DOCUMENT_EVIDENCE ? null : BigDecimal.TEN.setScale(12),
                rowKind == ItemRowKind.DOCUMENT_EVIDENCE ? null : BigDecimal.TEN.setScale(12),
                null, null, ControlSource.NONE, ValuationStatus.UNAVAILABLE_MISSING,
                null, null, null, false, null, null, null, false,
                null, false, false, PracticeRevenueValuationService.ReasonCode.GL_CONTROL_MISSING);
    }

    private static RegisteredDeliveryEvidenceResolver.ResolvedDelivery delivery(
            String work, String consultant, LocalDate date, String duration, String rate,
            String value, RegisteredDeliveryEvidenceResolver.RateResolutionStatus status) {
        return new RegisteredDeliveryEvidenceResolver.ResolvedDelivery(work, consultant, consultant, date,
                "task", "project", "contract",
                duration == null ? null : new BigDecimal(duration).setScale(6),
                rate == null ? null : new BigDecimal(rate).setScale(6),
                value == null ? null : new BigDecimal(value).setScale(12), status);
    }
}
