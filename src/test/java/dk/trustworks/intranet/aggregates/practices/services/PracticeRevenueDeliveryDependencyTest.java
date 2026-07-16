package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.model.PracticeRevenueDependency;
import dk.trustworks.intranet.aggregates.practices.model.PracticeRevenueItem;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigInteger;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class PracticeRevenueDeliveryDependencyTest {

    @Test
    void deliverySeedExpandsToEveryMutationLookupAndCarriesExactIntervals() {
        LocalDate delivery = LocalDate.of(2026, 6, 13);
        var seed = new PracticeRevenueMaterializationService.DeliveryDependencySeed(
                "work", "registrant", "effective", delivery, delivery.plusDays(1),
                "task", "project", "contract-project", "contract", "contract-consultant",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));

        List<PracticeRevenueMaterializationService.DependencyEnvelope> dependencies =
                PracticeRevenueMaterializationService.DependencyEnvelope.delivery(
                        item(), seed, "basis-generation");
        Map<String,PracticeRevenueMaterializationService.DependencyEnvelope> byKind = dependencies.stream()
                .collect(Collectors.toMap(
                        PracticeRevenueMaterializationService.DependencyEnvelope::kind,
                        Function.identity()));

        assertEquals(List.of("WORK", "DELIVERY_INTERVAL", "TASK", "PROJECT",
                        "CONTRACT_PROJECT", "CONTRACT", "CONTRACT_CONSULTANT", "PRACTICE_BASIS"),
                dependencies.stream().map(PracticeRevenueMaterializationService.DependencyEnvelope::kind)
                        .toList());
        var work = byKind.get("WORK");
        assertEquals("DELIVERY_EVIDENCE", work.sourceCategory());
        assertEquals("work", work.sourceWorkUuid());
        assertEquals("registrant", work.sourceUserUuid());
        assertEquals("effective", work.sourceCapacityUserUuid());
        assertEquals(delivery, work.deliveryStartDate());
        assertEquals(delivery.plusDays(1), work.deliveryEndDate());
        assertEquals("task", work.sourceTaskUuid());
        assertEquals("project", work.sourceProjectUuid());
        assertEquals("contract-project", work.sourceContractProjectUuid());
        assertEquals("contract", work.sourceContractUuid());
        assertEquals("contract-consultant", work.sourceContractConsultantUuid());
        assertEquals("PRACTICE_BASIS_INPUT", byKind.get("PRACTICE_BASIS").sourceCategory());
        assertTrue(dependencies.stream().allMatch(value -> value.fingerprint().length() == 64));
    }

    @Test
    void missingOptionalRoutingKeysRemainAConservativeIntervalDependency() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        var seed = new PracticeRevenueMaterializationService.DeliveryDependencySeed(
                null, "consultant", "consultant", start, start.plusMonths(1),
                null, "project", null, "contract", null, start, start.plusMonths(1));

        List<PracticeRevenueMaterializationService.DependencyEnvelope> dependencies =
                PracticeRevenueMaterializationService.DependencyEnvelope.delivery(item(), seed, "basis");

        assertTrue(dependencies.stream().noneMatch(value -> "WORK".equals(value.kind())));
        assertNotNull(dependencies.stream().filter(value -> "DELIVERY_INTERVAL".equals(value.kind()))
                .findFirst().orElseThrow().key());
        assertTrue(dependencies.stream().anyMatch(value -> "PROJECT".equals(value.kind())));
        assertTrue(dependencies.stream().anyMatch(value -> "CONTRACT".equals(value.kind())));
    }

    @Test
    void relevantFinalScanCleansOwnedAttemptWithoutThrowingAwayTheCommittedInvalidation() {
        PracticeRevenueMaterializationService service = spy(new PracticeRevenueMaterializationService());
        var versions = new EnumMap<PracticeRevenueDirtyMarker.Source,BigInteger>(
                PracticeRevenueDirtyMarker.Source.class);
        for (var source : PracticeRevenueDirtyMarker.Source.values()) versions.put(source, BigInteger.ONE);
        var attempt = new PracticeRevenueMaterializationService.Attempt("generation", "owner",
                BigInteger.ONE, LocalDateTime.parse("2026-07-16T08:00:00"), "basis",
                BigInteger.ONE, "vector", BigInteger.ONE, BigInteger.ONE, Map.copyOf(versions));
        var scan = new PracticeRevenueDirtyMarker.DeliveryPollResult(false, true, BigInteger.TEN,
                BigInteger.valueOf(11), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1));
        doNothing().when(service).failAndCleanup(attempt, "DELIVERY_EVIDENCE_ADVANCED");

        assertTrue(service.supersedeForDeliveryAdvance(attempt, scan));

        verify(service).failAndCleanup(attempt, "DELIVERY_EVIDENCE_ADVANCED");
    }

    @Test
    void persistenceCopiesEveryDeliveryLookupFieldIntoTheInternalEntity() {
        EntityManager em = mock(EntityManager.class);
        PracticeRevenueMaterializationService service = new PracticeRevenueMaterializationService();
        service.em = em;
        LocalDate delivery = LocalDate.of(2026, 6, 13);
        var seed = new PracticeRevenueMaterializationService.DeliveryDependencySeed(
                "work", "registrant", "effective", delivery, delivery.plusDays(1),
                "task", "project", "contract-project", "contract", "contract-consultant",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));
        var dependency = PracticeRevenueMaterializationService.DependencyEnvelope
                .delivery(item(), seed, "basis").getFirst();
        var document = new PracticeRevenueValuationService.DocumentValuation(
                "document", LocalDate.of(2026, 6, 1),
                PracticeRevenueValuationService.DocumentType.INVOICE, List.of(item()),
                BigDecimal.TEN.setScale(2), null, "voucher", BigDecimal.TEN,
                BigDecimal.TEN.setScale(2), PracticeRevenueValuationService.SourceStatus.COMPLETE,
                PracticeRevenueValuationService.ReasonCode.NONE);
        var candidate = new PracticeRevenueMaterializationService.BuildCandidate(
                LocalDateTime.parse("2026-07-16T08:00:00"), LocalDate.of(2021, 7, 1),
                LocalDate.of(2026, 6, 1), unavailableWindow(), unavailableWindow(), 1,
                List.of(new PracticeRevenueMaterializationService.ItemEnvelope(
                        "company", "CREATED", item(), document,
                        new PracticeRevenueAllocationService.EvidenceScope(
                                PracticeRevenueAllocationService.SegmentId.PM, "HISTORY", "INTERNAL",
                                PracticeRevenueAllocationService.ScopeResolutionStatus.RESOLVED,
                                PracticeRevenueAllocationService.ReasonCode.NONE))),
                List.of(), List.of(dependency),
                1, 0, 0, 0, 0, 0, 0, 0, BigDecimal.TEN.setScale(2),
                BigDecimal.TEN.setScale(2), BigDecimal.TEN.setScale(2), BigDecimal.ZERO.setScale(2));
        var versions = new EnumMap<PracticeRevenueDirtyMarker.Source,BigInteger>(
                PracticeRevenueDirtyMarker.Source.class);
        for (var source : PracticeRevenueDirtyMarker.Source.values()) versions.put(source, BigInteger.ONE);
        var attempt = new PracticeRevenueMaterializationService.Attempt("generation", "owner",
                BigInteger.ONE, LocalDateTime.parse("2026-07-16T08:00:00"), "basis",
                BigInteger.ONE, "vector", BigInteger.ONE, BigInteger.ONE, Map.copyOf(versions));

        service.persist(attempt, candidate);

        ArgumentCaptor<Object> persisted = ArgumentCaptor.forClass(Object.class);
        verify(em, org.mockito.Mockito.atLeastOnce()).persist(persisted.capture());
        PracticeRevenueDependency row = persisted.getAllValues().stream()
                .filter(PracticeRevenueDependency.class::isInstance)
                .map(PracticeRevenueDependency.class::cast).findFirst().orElseThrow();
        assertEquals("work", row.sourceWorkUuid);
        assertEquals("registrant", row.sourceUserUuid);
        assertEquals("effective", row.sourceCapacityUserUuid);
        assertEquals("task", row.sourceTaskUuid);
        assertEquals("project", row.sourceProjectUuid);
        assertEquals("contract-project", row.sourceContractProjectUuid);
        assertEquals("contract", row.sourceContractUuid);
        assertEquals("contract-consultant", row.sourceContractConsultantUuid);
        assertEquals(delivery, row.deliveryStartDate);
        assertEquals(delivery.plusDays(1), row.deliveryEndDate);
        PracticeRevenueItem itemRow = persisted.getAllValues().stream()
                .filter(PracticeRevenueItem.class::isInstance)
                .map(PracticeRevenueItem.class::cast).findFirst().orElseThrow();
        assertEquals("PM", itemRow.evidenceResolvedSegment);
        assertEquals("HISTORY", itemRow.evidencePracticeBasis);
        assertEquals("INTERNAL", itemRow.evidenceConsultantTypeBasis);
        assertEquals("RESOLVED", itemRow.scopeResolutionStatus);
    }

    private static PracticeRevenueMaterializationService.Window unavailableWindow() {
        return new PracticeRevenueMaterializationService.Window(false, "NO_WINDOW",
                null, null, null, null, null);
    }

    private static PracticeRevenueValuationService.ItemControl item() {
        return new PracticeRevenueValuationService.ItemControl(
                "SOURCE_ITEM:item", "item", "document", LocalDate.of(2026, 6, 1),
                PracticeRevenueValuationService.ItemRowKind.SOURCE_ITEM,
                PracticeRevenueValuationService.ItemCategory.DELIVERY_BASE, null, "DKK",
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN.setScale(2),
                BigDecimal.TEN.setScale(2), PracticeRevenueValuationService.ControlSource.ECONOMIC_GL,
                PracticeRevenueValuationService.ValuationStatus.CONFIRMED_GL,
                BigDecimal.TEN.setScale(4), BigDecimal.ZERO.setScale(4), BigDecimal.ONE,
                true, BigDecimal.TEN, BigDecimal.TEN.setScale(2), BigDecimal.ZERO,
                false, BigDecimal.ONE, false, false, PracticeRevenueValuationService.ReasonCode.NONE);
    }
}
