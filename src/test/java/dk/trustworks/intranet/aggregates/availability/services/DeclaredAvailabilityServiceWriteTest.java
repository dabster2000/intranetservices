package dk.trustworks.intranet.aggregates.availability.services;

import dk.trustworks.intranet.aggregates.availability.config.DeclaredAvailabilityPolicy;
import dk.trustworks.intranet.aggregates.availability.dto.DeclaredAvailabilityCopyRequest;
import dk.trustworks.intranet.aggregates.availability.dto.DeclaredAvailabilityDTO;
import dk.trustworks.intranet.aggregates.availability.dto.DeclaredAvailabilityUpsertRequest;
import dk.trustworks.intranet.aggregates.availability.model.UserDeclaredAvailability;
import dk.trustworks.intranet.aggregates.availability.model.UserDeclaredAvailability.Source;
import dk.trustworks.intranet.dao.workservice.services.MonthSubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Write-path unit tests for {@link DeclaredAvailabilityService}, no database.
 *
 * <p><b>The regression these exist for.</b> Every {@code nullable = false} column on
 * {@link UserDeclaredAvailability} has to hold a value <em>before</em> {@code persist()} is
 * called, not merely before the transaction commits. The id is assigned (a String uuid, no
 * generator), so Hibernate resolves the insert action inside {@code persist()} itself and runs
 * {@code Nullability.checkNullability} there. A row persisted half-filled — {@code hours},
 * {@code source} and {@code updated_at} still null — throws immediately:
 *
 * <pre>org.hibernate.PropertyValueException: not-null property references a null or transient
 * value for entity ...UserDeclaredAvailability.hours</pre>
 *
 * <p>That is what took every declared-availability save down in production on 2026-09-07 with a
 * 500: the table was empty, so every write was an insert, so every write threw. The assertions
 * below snapshot each row <em>at the moment {@code persist()} is invoked</em> — checking the
 * state afterwards would pass on the broken code, because the setters do run, just too late.
 */
class DeclaredAvailabilityServiceWriteTest {

    private static final String USER = "dd185429-a11d-452d-8faf-2087f95a85f2";
    private static final String ACTOR = "dd185429-a11d-452d-8faf-2087f95a85f2";
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);

    private DeclaredAvailabilityService service;

    /**
     * The state of each row as {@code persist()} saw it. These are <em>copies</em> on purpose:
     * the broken code persists the row and then sets the missing columns on that same object, so
     * holding the reference would let a late setter fill the snapshot in and the test would pass
     * on the defect it exists to catch.
     */
    private final List<Snapshot> persistedAs = new ArrayList<>();

    /** One row's columns, frozen at the instant {@code persist()} was invoked. */
    private record Snapshot(String uuid, String useruuid, LocalDate day, BigDecimal hours,
                            Source source, String note, LocalDateTime createdAt,
                            LocalDateTime updatedAt, String updatedBy) {

        static Snapshot of(UserDeclaredAvailability row) {
            return new Snapshot(row.getUuid(), row.getUseruuid(), row.getDay(), row.getHours(),
                    row.getSource(), row.getNote(), row.getCreatedAt(), row.getUpdatedAt(),
                    row.getUpdatedBy());
        }
    }

    @BeforeEach
    void setUp() {
        service = new DeclaredAvailabilityService();
        service.monthSubmissionService = mock(MonthSubmissionService.class);
        when(service.monthSubmissionService.isMonthLocked(anyString(), anyInt(), anyInt())).thenReturn(false);
        service.policy = mock(DeclaredAvailabilityPolicy.class);
        // SHADOW: recalculateAfterCommit returns before it needs a transaction or an executor.
        when(service.policy.isLive()).thenReturn(false);
        persistedAs.clear();
    }

    // ── upsert ──────────────────────────────────────────────────────────────

    @Test
    void newDayIsFullyPopulatedBeforePersist() {
        List<DeclaredAvailabilityUpsertRequest> items = List.of(
                new DeclaredAvailabilityUpsertRequest(MONDAY, new BigDecimal("7.50"), "kunde"),
                new DeclaredAvailabilityUpsertRequest(MONDAY.plusDays(1), BigDecimal.ZERO, null));

        List<DeclaredAvailabilityDTO> saved;
        try (MockedStatic<UserDeclaredAvailability> statics = mockStatic(UserDeclaredAvailability.class);
             MockedConstruction<UserDeclaredAvailability> ignored = interceptingConstruction()) {
            statics.when(() -> UserDeclaredAvailability.mapForRange(anyString(), any(), any()))
                    .thenReturn(new HashMap<>());

            saved = service.upsert(USER, items, Source.SELF, ACTOR);
        }

        assertEquals(2, persistedAs.size(), "both new days must be inserted");
        for (Snapshot row : persistedAs) {
            assertNotNull(row.hours(), "hours is NOT NULL — must be set before persist()");
            assertNotNull(row.source(), "source is NOT NULL — must be set before persist()");
            assertNotNull(row.updatedAt(), "updated_at is NOT NULL — must be set before persist()");
            assertNotNull(row.uuid(), "uuid is the assigned id — must be set before persist()");
            assertNotNull(row.useruuid(), "useruuid is NOT NULL — must be set before persist()");
            assertNotNull(row.day(), "day is NOT NULL — must be set before persist()");
            assertNotNull(row.createdAt(), "created_at is NOT NULL — must be set before persist()");
        }
        assertEquals(new BigDecimal("7.50"), persistedAs.get(0).hours());
        assertEquals(Source.SELF, persistedAs.get(0).source());
        assertEquals(USER, persistedAs.get(0).useruuid());
        assertEquals(ACTOR, persistedAs.get(0).updatedBy());
        assertEquals("kunde", persistedAs.get(0).note());
        assertEquals(2, saved.size());
    }

    @Test
    void zeroHoursIsARealDeclarationAndIsInserted() {
        List<DeclaredAvailabilityUpsertRequest> items =
                List.of(new DeclaredAvailabilityUpsertRequest(MONDAY, BigDecimal.ZERO, null));

        try (MockedStatic<UserDeclaredAvailability> statics = mockStatic(UserDeclaredAvailability.class);
             MockedConstruction<UserDeclaredAvailability> ignored = interceptingConstruction()) {
            statics.when(() -> UserDeclaredAvailability.mapForRange(anyString(), any(), any()))
                    .thenReturn(new HashMap<>());

            service.upsert(USER, items, Source.SELF, ACTOR);
        }

        assertEquals(1, persistedAs.size());
        assertEquals(0, BigDecimal.ZERO.compareTo(persistedAs.get(0).hours()));
        assertNotNull(persistedAs.get(0).source());
    }

    @Test
    void existingDayIsUpdatedInPlaceWithoutAnInsert() {
        UserDeclaredAvailability existing = spy(row(MONDAY, new BigDecimal("3.75"), Source.SELF));
        Map<LocalDate, UserDeclaredAvailability> byDay = new HashMap<>();
        byDay.put(MONDAY, existing);

        try (MockedStatic<UserDeclaredAvailability> statics = mockStatic(UserDeclaredAvailability.class);
             MockedConstruction<UserDeclaredAvailability> ignored = interceptingConstruction()) {
            statics.when(() -> UserDeclaredAvailability.mapForRange(anyString(), any(), any()))
                    .thenReturn(byDay);

            List<DeclaredAvailabilityDTO> saved = service.upsert(USER,
                    List.of(new DeclaredAvailabilityUpsertRequest(MONDAY, new BigDecimal("7.50"), null)),
                    Source.TEAMLEAD, "lead-uuid");

            assertEquals(1, saved.size());
        }

        assertTrue(persistedAs.isEmpty(), "an existing row is dirty-checked, never re-inserted");
        assertEquals(new BigDecimal("7.50"), existing.getHours());
        assertEquals(Source.TEAMLEAD, existing.getSource());
        assertEquals("lead-uuid", existing.getUpdatedBy());
    }

    @Test
    void nullHoursClearsAnExistingDayAndNeverInserts() {
        UserDeclaredAvailability existing = spy(row(MONDAY, new BigDecimal("7.50"), Source.SELF));
        doNothing().when(existing).delete();
        Map<LocalDate, UserDeclaredAvailability> byDay = new HashMap<>();
        byDay.put(MONDAY, existing);

        List<DeclaredAvailabilityDTO> saved;
        try (MockedStatic<UserDeclaredAvailability> statics = mockStatic(UserDeclaredAvailability.class);
             MockedConstruction<UserDeclaredAvailability> ignored = interceptingConstruction()) {
            statics.when(() -> UserDeclaredAvailability.mapForRange(anyString(), any(), any()))
                    .thenReturn(byDay);

            saved = service.upsert(USER,
                    List.of(new DeclaredAvailabilityUpsertRequest(MONDAY, null, null)),
                    Source.SELF, ACTOR);
        }

        verify(existing).delete();
        assertTrue(saved.isEmpty(), "a cleared day is not a saved row");
        assertTrue(persistedAs.isEmpty(), "a clear must never insert a null-hours row");
    }

    @Test
    void nullHoursOnAnUndeclaredDayIsANoOp() {
        try (MockedStatic<UserDeclaredAvailability> statics = mockStatic(UserDeclaredAvailability.class);
             MockedConstruction<UserDeclaredAvailability> ignored = interceptingConstruction()) {
            statics.when(() -> UserDeclaredAvailability.mapForRange(anyString(), any(), any()))
                    .thenReturn(new HashMap<>());

            List<DeclaredAvailabilityDTO> saved = service.upsert(USER,
                    List.of(new DeclaredAvailabilityUpsertRequest(MONDAY, null, null)),
                    Source.SELF, ACTOR);

            assertTrue(saved.isEmpty());
        }
        assertTrue(persistedAs.isEmpty(), "clearing a day that has no row must insert nothing");
    }

    @Test
    void oneBatchCarriesAdditionsChangesAndClearsTogether() {
        UserDeclaredAvailability change = spy(row(MONDAY, new BigDecimal("3.75"), Source.SELF));
        UserDeclaredAvailability clear = spy(row(MONDAY.plusDays(1), new BigDecimal("7.50"), Source.SELF));
        doNothing().when(clear).delete();
        Map<LocalDate, UserDeclaredAvailability> byDay = new HashMap<>();
        byDay.put(MONDAY, change);
        byDay.put(MONDAY.plusDays(1), clear);

        List<DeclaredAvailabilityDTO> saved;
        try (MockedStatic<UserDeclaredAvailability> statics = mockStatic(UserDeclaredAvailability.class);
             MockedConstruction<UserDeclaredAvailability> ignored = interceptingConstruction()) {
            statics.when(() -> UserDeclaredAvailability.mapForRange(anyString(), any(), any()))
                    .thenReturn(byDay);

            saved = service.upsert(USER, List.of(
                    new DeclaredAvailabilityUpsertRequest(MONDAY, new BigDecimal("7.50"), null),
                    new DeclaredAvailabilityUpsertRequest(MONDAY.plusDays(1), null, null),
                    new DeclaredAvailabilityUpsertRequest(MONDAY.plusDays(2), new BigDecimal("7.50"), null)),
                    Source.SELF, ACTOR);
        }

        verify(clear).delete();
        verify(change, never()).delete();
        assertEquals(2, saved.size(), "the change and the addition, not the clear");
        assertEquals(1, persistedAs.size(), "only the previously undeclared day is inserted");
        assertEquals(MONDAY.plusDays(2), persistedAs.get(0).day());
        assertNotNull(persistedAs.get(0).hours());
        assertNotNull(persistedAs.get(0).source());
        assertNotNull(persistedAs.get(0).updatedAt());
    }

    // ── copy-forward ────────────────────────────────────────────────────────

    @Test
    void copyForwardPopulatesEveryNewTargetRowBeforePersist() {
        LocalDate source = MONDAY;
        LocalDate target = MONDAY.plusWeeks(1);
        UserDeclaredAvailability src = row(source, new BigDecimal("7.50"), Source.SELF);
        src.setNote("kunde");
        Map<LocalDate, UserDeclaredAvailability> sourceWeek = new HashMap<>();
        sourceWeek.put(source, src);

        try (MockedStatic<UserDeclaredAvailability> statics = mockStatic(UserDeclaredAvailability.class);
             MockedConstruction<UserDeclaredAvailability> ignored = interceptingConstruction()) {
            statics.when(() -> UserDeclaredAvailability.mapForRange(USER, source, source.plusDays(6)))
                    .thenReturn(sourceWeek);
            statics.when(() -> UserDeclaredAvailability.mapForRange(USER, target, target.plusDays(6)))
                    .thenReturn(new HashMap<>());

            List<DeclaredAvailabilityDTO> result = service.copyForward(USER,
                    new DeclaredAvailabilityCopyRequest(source, List.of(target)), ACTOR);

            assertEquals(1, result.size());
        }

        assertEquals(1, persistedAs.size(), "the one declared source day is copied onto the target week");
        Snapshot copied = persistedAs.get(0);
        assertNotNull(copied.hours(), "hours is NOT NULL — must be set before persist()");
        assertNotNull(copied.source(), "source is NOT NULL — must be set before persist()");
        assertNotNull(copied.updatedAt(), "updated_at is NOT NULL — must be set before persist()");
        assertNotNull(copied.uuid());
        assertNotNull(copied.createdAt());
        assertEquals(target, copied.day());
        assertEquals(new BigDecimal("7.50"), copied.hours());
        assertEquals(Source.SYSTEM, copied.source(), "a copy is always SYSTEM-sourced");
        assertEquals("kunde", copied.note());
    }

    // ── harness ─────────────────────────────────────────────────────────────

    /**
     * Intercepts {@code new UserDeclaredAvailability()} inside the block so {@code persist()}
     * records the row instead of reaching Hibernate. Real method bodies still run, so the
     * service's setters write real fields and the snapshot is the row as the database would
     * have received it.
     *
     * <p>Because the constructor is intercepted, every fixture row this test needs must be
     * built <em>before</em> the block opens.
     */
    private MockedConstruction<UserDeclaredAvailability> interceptingConstruction() {
        return mockConstruction(UserDeclaredAvailability.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS),
                (row, context) -> doAnswer(invocation -> {
                    persistedAs.add(Snapshot.of(row));
                    return null;
                }).when(row).persist());
    }

    private static UserDeclaredAvailability row(LocalDate day, BigDecimal hours, Source source) {
        UserDeclaredAvailability row = new UserDeclaredAvailability();
        row.setUuid("row-" + day);
        row.setUseruuid(USER);
        row.setDay(day);
        row.setHours(hours);
        row.setSource(source);
        row.setCreatedAt(LocalDateTime.of(2026, 9, 1, 8, 0));
        row.setUpdatedAt(LocalDateTime.of(2026, 9, 1, 8, 0));
        return row;
    }
}
