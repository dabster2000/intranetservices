package dk.trustworks.intranet.utils.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.bugreport.dto.AutoFixStatsDTO;
import dk.trustworks.intranet.aggregates.bugreport.dto.AutoFixTaskDTO;
import dk.trustworks.intranet.aggregates.bugreport.dto.BugReportCommentDTO;
import dk.trustworks.intranet.aggregates.bugreport.dto.BugReportDTO;
import dk.trustworks.intranet.aggregates.bugreport.dto.BugReportNotificationDTO;
import dk.trustworks.intranet.recruitmentservice.dto.GdprQueueResponse;
import dk.trustworks.intranet.recruitmentservice.dto.LandingResponse;
import dk.trustworks.intranet.recruitmentservice.dto.TimelineEvent;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.utils.JavaTimeObjectMapperCustomizer;
import dk.trustworks.intranet.utils.TemporalParams;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the temporal wire contract: an {@link UtcInstant} {@code LocalDateTime} carries a
 * {@code Z}; every other temporal shape is untouched.
 *
 * <p>Plain JUnit on purpose — it must run in the DB-free tier
 * ({@code ./mvnw test -DexcludedGroups=io.quarkus.test.junit.QuarkusTest}) that the CI
 * deploy gate executes.
 */
class UtcInstantWireFormatTest {

    private static ObjectMapper mapper() {
        ObjectMapper m = new ObjectMapper();
        new JavaTimeObjectMapperCustomizer().customize(m);
        return m;
    }

    record InstantHolder(@UtcInstant LocalDateTime occurredAt) {
    }

    record PlainHolder(LocalDateTime scheduledAt) {
    }

    record DateOnlyHolder(LocalDate registered) {
    }

    @Test
    void annotated_instant_serializes_with_Z() throws Exception {
        // recruitment_events seq 858 — the production value behind the reported bug.
        assertEquals("{\"occurredAt\":\"2026-08-16T16:27:00.925Z\"}",
                mapper().writeValueAsString(new InstantHolder(
                        LocalDateTime.of(2026, 8, 16, 16, 27, 0, 925_000_000))));
    }

    @Test
    void annotated_instant_without_fraction_still_carries_Z() throws Exception {
        assertEquals("{\"occurredAt\":\"2026-06-08T09:23:25Z\"}",
                mapper().writeValueAsString(new InstantHolder(
                        LocalDateTime.of(2026, 6, 8, 9, 23, 25))));
    }

    @Test
    void annotated_instant_never_emits_a_numeric_offset() throws Exception {
        // The frontend's interview work-arounds test endsWith('Z'); a '+00:00' suffix
        // slips past them and then yields Invalid Date.
        String json = mapper().writeValueAsString(
                new InstantHolder(LocalDateTime.of(2026, 1, 2, 3, 4, 5)));
        assertTrue(json.endsWith("Z\"}"), json);
        assertFalse(json.contains("+00:00"), json);
    }

    @Test
    void unannotated_localDateTime_is_unchanged() throws Exception {
        assertEquals("{\"scheduledAt\":\"2026-08-20T10:00:00\"}",
                mapper().writeValueAsString(new PlainHolder(LocalDateTime.of(2026, 8, 20, 10, 0))));
    }

    @Test
    void localDate_is_unchanged() throws Exception {
        assertEquals("{\"registered\":\"2026-06-30\"}",
                mapper().writeValueAsString(new DateOnlyHolder(LocalDate.of(2026, 6, 30))));
    }

    @Test
    void annotated_instant_roundTrips_through_every_shape() throws Exception {
        ObjectMapper m = mapper();
        LocalDateTime original = LocalDateTime.of(2026, 8, 16, 16, 27, 0, 925_000_000);
        assertEquals(original,
                m.readValue("{\"occurredAt\":\"2026-08-16T16:27:00.925Z\"}", InstantHolder.class).occurredAt());
        assertEquals(original,
                m.readValue("{\"occurredAt\":\"2026-08-16T16:27:00.925\"}", InstantHolder.class).occurredAt());
        assertEquals(original,
                m.readValue("{\"occurredAt\":\"2026-08-16T18:27:00.925+02:00\"}", InstantHolder.class).occurredAt());
    }

    @Test
    void wave1_inventory_is_pinned() throws Exception {
        assertRecordComponentAnnotated(TimelineEvent.class, "occurredAt");
        assertRecordComponentAnnotated(LandingResponse.LandingActivity.class, "occurredAt");
        assertRecordComponentAnnotated(GdprQueueResponse.AnonymizationRow.class, "occurredAt");
        assertFieldAnnotated(dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent.class, "occurredAt");
    }

    @Test
    void wave2_bugReport_inventory_is_pinned() throws Exception {
        assertRecordComponentAnnotated(BugReportDTO.class, "createdAt");
        assertRecordComponentAnnotated(BugReportDTO.class, "updatedAt");
        assertRecordComponentAnnotated(BugReportCommentDTO.class, "createdAt");
        assertRecordComponentAnnotated(AutoFixStatsDTO.class, "lastSuccessfulFixAt");
        assertFieldAnnotated(AutoFixTaskDTO.class, "createdAt");
        assertFieldAnnotated(AutoFixTaskDTO.class, "startedAt");
        assertFieldAnnotated(AutoFixTaskDTO.class, "completedAt");
        assertFieldAnnotated(AutoFixTaskDTO.class, "heartbeatAt");
    }

    @Test
    void wave2_bugReportDto_emits_both_timestamps_with_Z() throws Exception {
        String json = mapper().writeValueAsString(new BugReportDTO(
                "b-1", "r-1", "Reporter", null, null, "DRAFT", "t", "d", null, null, null,
                "MEDIUM", null, null, null, null, null, null, null, null, null, null,
                LocalDateTime.of(2026, 8, 16, 16, 27, 0, 925_000_000),
                LocalDateTime.of(2026, 8, 16, 16, 31, 12),
                0L));
        assertTrue(json.contains("\"createdAt\":\"2026-08-16T16:27:00.925Z\""), json);
        assertTrue(json.contains("\"updatedAt\":\"2026-08-16T16:31:12Z\""), json);
    }

    @Test
    void wave2_bugReportCommentDto_emits_createdAt_with_Z() throws Exception {
        String json = mapper().writeValueAsString(new BugReportCommentDTO(
                "c-1", "b-1", "a-1", "Author", "content", false,
                LocalDateTime.of(2026, 8, 16, 16, 27, 0, 925_000_000)));
        assertTrue(json.contains("\"createdAt\":\"2026-08-16T16:27:00.925Z\""), json);
    }

    @Test
    void wave2_autoFixStatsDto_emits_lastSuccessfulFixAt_with_Z() throws Exception {
        String json = mapper().writeValueAsString(new AutoFixStatsDTO(
                0, "idle", null, null,
                LocalDateTime.of(2026, 8, 16, 16, 27, 0, 925_000_000),
                null, null, 1.0d, 1, 1, BigDecimal.ZERO));
        assertTrue(json.contains("\"last_successful_fix_at\":\"2026-08-16T16:27:00.925Z\""), json);
    }

    /**
     * {@link AutoFixTaskDTO} is a getter-based POJO, not a record — the annotations sit on the
     * private fields while Jackson serializes through the getters. Asserting on real output
     * rather than on reflection is what proves the two halves were merged into one property.
     */
    @Test
    void wave2_autoFixTaskDto_emits_all_four_timestamps_with_Z() throws Exception {
        var dto = new AutoFixTaskDTO();
        dto.setTaskId("t-1");
        dto.setCreatedAt(LocalDateTime.of(2026, 8, 16, 16, 27, 0, 925_000_000));
        dto.setStartedAt(LocalDateTime.of(2026, 8, 16, 16, 28, 0));
        dto.setCompletedAt(LocalDateTime.of(2026, 8, 16, 16, 45, 30, 500_000_000));
        dto.setHeartbeatAt(LocalDateTime.of(2026, 8, 16, 16, 45, 0));

        String json = mapper().writeValueAsString(dto);

        assertTrue(json.contains("\"created_at\":\"2026-08-16T16:27:00.925Z\""), json);
        assertTrue(json.contains("\"started_at\":\"2026-08-16T16:28:00Z\""), json);
        // ISO_INSTANT emits the fraction in groups of three, so .5s is ".500", not ".5".
        assertTrue(json.contains("\"completed_at\":\"2026-08-16T16:45:30.500Z\""), json);
        assertTrue(json.contains("\"heartbeat_at\":\"2026-08-16T16:45:00Z\""), json);
        assertFalse(json.contains("+00:00"), json);
    }

    /**
     * {@code TriageResponse.updatedAt} is a String, not a {@code LocalDateTime}, so no
     * annotation can reach it — {@code BugReportService} formats it by hand. It is the same
     * optimistic-lock token as {@link BugReportDTO#updatedAt()} and must not travel in a
     * second shape, so this pins the expression the service uses.
     */
    @Test
    void triageResponse_token_shape_matches_the_dto_and_round_trips() {
        LocalDateTime stored = LocalDateTime.of(2026, 8, 16, 16, 27, 0, 925_000_000);

        String token = stored.toInstant(ZoneOffset.UTC).toString();

        assertEquals("2026-08-16T16:27:00.925Z", token);
        assertEquals(stored, TemporalParams.parseUtcInstant(token));
    }

    /**
     * {@code BugReportNotificationDTO.createdAt} is already an {@link OffsetDateTime} —
     * {@code BugReportService} builds it with {@code .atOffset(ZoneOffset.UTC)}. It carries a
     * zone designator without any help, so annotating it (or appending a {@code Z} on the
     * frontend) would double-shift it.
     */
    @Test
    void bugReportNotification_is_already_zoned_and_must_stay_unannotated() throws Exception {
        assertRecordComponentNotAnnotated(BugReportNotificationDTO.class, "createdAt");

        String json = mapper().writeValueAsString(new BugReportNotificationDTO(
                "n-1", "u-1", "b-1", "STATUS_CHANGED", "message", false,
                LocalDateTime.of(2026, 8, 16, 16, 27, 0, 925_000_000).atOffset(ZoneOffset.UTC)));
        assertTrue(json.contains("\"createdAt\":\"2026-08-16T16:27:00.925Z\""), json);
    }

    /**
     * NEGATIVE guard. These are Europe/Copenhagen wall-clock values entered by humans, and
     * are compared directly against Microsoft Graph free/busy requested with
     * {@code Prefer: outlook.timezone="Europe/Copenhagen"}. Annotating any of them shifts
     * real interview and deadline times by the UTC offset on a page an external job
     * candidate reads.
     */
    @Test
    void wallClock_family_must_never_be_annotated() throws Exception {
        assertFieldNotAnnotated(RecruitmentInterview.class, "scheduledAt");
        assertFieldNotAnnotated(RecruitmentSchedulingRequest.class, "candidateDeadline");
        assertFieldNotAnnotated(RecruitmentProposedSlot.class, "slotStart");
        assertFieldNotAnnotated(RecruitmentProposedSlot.class, "slotEnd");
        assertRecordComponentNotAnnotated(LandingResponse.LandingInterview.class, "scheduledAt");
    }

    private static RecordComponent component(Class<?> type, String name) {
        for (RecordComponent rc : type.getRecordComponents()) {
            if (rc.getName().equals(name)) {
                return rc;
            }
        }
        throw new AssertionError("no record component " + type.getSimpleName() + "." + name);
    }

    private static void assertRecordComponentAnnotated(Class<?> type, String name) {
        assertTrue(component(type, name).isAnnotationPresent(UtcInstant.class),
                type.getSimpleName() + "." + name + " must stay @UtcInstant");
    }

    private static void assertRecordComponentNotAnnotated(Class<?> type, String name) {
        assertFalse(component(type, name).isAnnotationPresent(UtcInstant.class),
                type.getSimpleName() + "." + name
                        + " is Europe/Copenhagen wall-clock, not a UTC instant");
    }

    private static void assertFieldAnnotated(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        assertTrue(f.isAnnotationPresent(UtcInstant.class),
                type.getSimpleName() + "." + name + " must stay @UtcInstant");
    }

    private static void assertFieldNotAnnotated(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        assertFalse(f.isAnnotationPresent(UtcInstant.class),
                type.getSimpleName() + "." + name
                        + " is Europe/Copenhagen wall-clock, not a UTC instant");
    }
}
