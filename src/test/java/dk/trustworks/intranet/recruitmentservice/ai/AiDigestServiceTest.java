package dk.trustworks.intranet.recruitmentservice.ai;

import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentPiiState;
import dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P24 §DoD — the two AI digests against the local DB with a mocked
 * OpenAI transport: event-derived idempotency per period, toggle
 * semantics, the numeric/enum-only prompt guarantee (sentinel fixture),
 * KPI payload correctness (baseline-delta — the shared local DB carries
 * real fact rows), the fiscal-quarter boundary July→June, and the
 * no-event-on-model-failure self-healing posture. All assertions are
 * scoped to fixture-distinctive dims or baseline deltas.
 */
@QuarkusTest
class AiDigestServiceTest {

    private static final String PIPELINE_FLAG = P8ProfileFixtures.PIPELINE_FLAG;
    private static final String WEEKLY_FLAG = "recruitment.ai.digest.weekly-funnel.enabled";
    private static final String REJECTION_FLAG = "recruitment.ai.digest.rejection-patterns.enabled";

    /** Fixed clocks — deterministic periods and windows, forever. */
    private static final LocalDate WEEKLY_TODAY = LocalDate.of(2026, 7, 20);      // 2026-W30
    private static final LocalDate REJECTION_TODAY = LocalDate.of(2026, 7, 3);    // in grace window
    private static final LocalDate OUTSIDE_GRACE = LocalDate.of(2026, 7, 20);     // past day 14

    private static final String NARRATIVE = "Ugen viste god fremdrift i tragten.";

    @Inject
    AiDigestService service;

    @Inject
    dk.trustworks.intranet.recruitmentservice.services.RecruitmentAnonymizerService anonymizer;

    @Inject
    EntityManager em;

    @InjectMock
    OpenAIService openAIService;

    /** Mocked so the anonymizer's S3 leg never touches storage. */
    @InjectMock
    @SuppressWarnings("unused")
    dk.trustworks.intranet.recruitmentservice.services.RecruitmentS3StorageService storageService;

    /** Mocked so the live SlackDigestRenderer can never touch real Slack. */
    @InjectMock
    @SuppressWarnings("unused")
    SlackService slackService;

    /** Distinctive dims for every seeded fact row — the cleanup key. */
    private String positionDim;
    private String sourceDim;
    private String sentinelCandidateUuid;

    private String previousPipeline;
    private String previousWeekly;
    private String previousRejection;

    @BeforeEach
    void seed() {
        positionDim = "P24-" + UUID.randomUUID().toString().substring(0, 12);
        sourceDim = "P24SRC" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        sentinelCandidateUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            previousPipeline = P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true");
            previousWeekly = P8ProfileFixtures.setFlag(em, WEEKLY_FLAG, "true");
            previousRejection = P8ProfileFixtures.setFlag(em, REJECTION_FLAG, "true");
            deleteDigestEvents();
            // A sentinel-named candidate proves the prompt path can never
            // reach candidate rows — the DTO guarantee, mirrored by data.
            P8ProfileFixtures.insertCandidate(em, sentinelCandidateUuid,
                    RecruitmentEventPiiAssertions.PII_SENTINEL + "-First",
                    RecruitmentEventPiiAssertions.PII_SENTINEL + "-Last",
                    "ACTIVE", null, null, "test");
            // Weekly window (relative to WEEKLY_TODAY): 2026-04..2026-07.
            insertFact("2026-07-01", "APPLICATION_CREATED", sourceDim, "", "", "", "", 7, "0");
            insertFact("2026-06-01", "STAGE_MOVED", "", "SCREENING", "INTERVIEW_1", "FORWARD", "", 3, "6.00");
            insertFact("2026-06-01", "TERMINAL", sourceDim, "SCREENING", "", "REJECTED", "PROFILE_MISMATCH", 2, "4.00");
            insertFact("2026-07-01", "HIRED", sourceDim, "", "", "", "", 1, "0");
            insertFact("2026-07-01", "SCORECARD_SUBMITTED", "", "", "", "web", "", 4, "0");
            insertFact("2026-07-01", "NUDGE_SENT", "", "", "", "", "SCORECARD_NUDGED", 2, "0");
            // Quarterly window (Apr–Jun 2026): applications for the source rate.
            insertFact("2026-05-01", "APPLICATION_CREATED", sourceDim, "", "", "", "", 5, "0");
        });
        when(openAIService.generatePlainText(anyString(), anyString(), anyString(),
                anyInt(), any(), anyBoolean())).thenReturn(NARRATIVE);
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_fact_monthly WHERE position_uuid = :p")
                    .setParameter("p", positionDim).executeUpdate();
            deleteDigestEvents();
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(sentinelCandidateUuid), List.of(), List.of(), null);
            P8ProfileFixtures.restoreFlag(em, PIPELINE_FLAG, previousPipeline);
            P8ProfileFixtures.restoreFlag(em, WEEKLY_FLAG, previousWeekly);
            P8ProfileFixtures.restoreFlag(em, REJECTION_FLAG, previousRejection);
        });
    }

    // =========================================================================
    // Weekly funnel digest
    // =========================================================================

    @Test
    void weeklyDigest_generatesOneEventWithAggregatePayload() {
        // The window sums INCLUDE the seeded fixture rows (queried after
        // seeding) — the service's KPIs must equal them exactly, and the
        // fixture guarantees each is at least the seeded amount.
        long windowApplications = windowSum("APPLICATION_CREATED", "2026-04-01", "2026-07-01");
        long windowHires = windowSum("HIRED", "2026-04-01", "2026-07-01");
        long windowNudges = windowSum("NUDGE_SENT", "2026-04-01", "2026-07-01");
        assertTrue(windowApplications >= 7 && windowHires >= 1 && windowNudges >= 2,
                "the seeded fixture rows must be inside the window");

        AiDigestService.DigestSummary summary = service.runWeeklyFunnel(WEEKLY_TODAY);

        assertTrue(summary.enabled());
        assertTrue(summary.generated());
        assertEquals("2026-W30", summary.period());

        RecruitmentEvent event = singleDigestEvent("AI_DIGEST_GENERATED");
        assertNull(event.getCandidateUuid(), "digest events carry no candidate subject");
        assertEquals(RecruitmentPiiState.NONE, event.getPiiState(),
                "narrative lives in payload BY DESIGN — aggregates only (plan §P24)");
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);

        Map<String, Object> payload = parse(event.getPayload());
        assertEquals(AiDigestService.KIND_WEEKLY_FUNNEL, payload.get("kind"));
        assertEquals("2026-W30", payload.get("period"));
        assertEquals("2026-04", payload.get("window_from"));
        assertEquals("2026-07", payload.get("window_to"));
        assertEquals(NARRATIVE, payload.get("narrative"));
        Map<String, Object> kpis = asMap(payload.get("kpis"));
        assertEquals(windowApplications, ((Number) kpis.get("applications")).longValue());
        assertEquals(windowHires, ((Number) kpis.get("hires")).longValue());
        assertEquals(windowNudges, ((Number) kpis.get("nudges")).longValue());

        // The prompt carries the projection's numbers — and can never
        // carry a name (the sentinel-named candidate exists in the DB).
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(openAIService).generatePlainText(anyString(), prompt.capture(), anyString(),
                anyInt(), any(), anyBoolean());
        assertTrue(prompt.getValue().contains("2026-07 " + sourceDim + ": 7"),
                "prompt must list the seeded per-source application aggregate");
        assertTrue(prompt.getValue().contains("SCORECARD_NUDGED"),
                "prompt must carry the SLA nudge stats (the P20→P24 carry-over)");
        assertFalse(prompt.getValue().contains(RecruitmentEventPiiAssertions.PII_SENTINEL),
                "prompt must be constructed exclusively from projection aggregates");
        assertFalse(prompt.getValue().contains("@"), "prompt must carry no email addresses");
    }

    @Test
    void weeklyDigest_idempotentPerIsoWeek() {
        assertTrue(service.runWeeklyFunnel(WEEKLY_TODAY).generated());
        AiDigestService.DigestSummary second = service.runWeeklyFunnel(WEEKLY_TODAY);

        assertTrue(second.enabled());
        assertFalse(second.generated(), "one digest per ISO week — the Monday re-run rule");
        verify(openAIService, times(1)).generatePlainText(anyString(), anyString(), anyString(),
                anyInt(), any(), anyBoolean());
        assertEquals(1, digestEventCount("AI_DIGEST_GENERATED"));
    }

    @Test
    void weeklyDigest_togglesOff_noModelCallNoEvent() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, WEEKLY_FLAG, "false"));
        assertFalse(service.runWeeklyFunnel(WEEKLY_TODAY).enabled());

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.setFlag(em, WEEKLY_FLAG, "true");
            P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "false");
        });
        assertFalse(service.runWeeklyFunnel(WEEKLY_TODAY).enabled(),
                "the pipeline flag hosts every side surface (P22/P23 precedent)");

        verifyNoInteractions(openAIService);
        assertEquals(0, digestEventCount("AI_DIGEST_GENERATED"));
    }

    @Test
    void weeklyDigest_modelFailure_noEvent_nextRunHeals() {
        when(openAIService.generatePlainText(anyString(), anyString(), anyString(),
                anyInt(), any(), anyBoolean())).thenReturn("");
        AiDigestService.DigestSummary failed = service.runWeeklyFunnel(WEEKLY_TODAY);
        assertTrue(failed.enabled());
        assertFalse(failed.generated());
        assertEquals(0, digestEventCount("AI_DIGEST_GENERATED"));

        // The next (daily) run finds no event for the week and retries.
        when(openAIService.generatePlainText(anyString(), anyString(), anyString(),
                anyInt(), any(), anyBoolean())).thenReturn(NARRATIVE);
        assertTrue(service.runWeeklyFunnel(WEEKLY_TODAY).generated());
        assertEquals(1, digestEventCount("AI_DIGEST_GENERATED"));
    }

    // =========================================================================
    // Quarterly rejection digest (fiscal July→June)
    // =========================================================================

    @Test
    void rejectionDigest_generatedForEndedFiscalQuarter_insideGraceWindow() {
        // Includes the seeded 2 rejections (queried after seeding).
        long windowRejections = windowRejections("2026-04-01", "2026-06-01");
        assertTrue(windowRejections >= 2, "the seeded rejections must be inside the window");

        AiDigestService.DigestSummary summary = service.runRejectionPatterns(REJECTION_TODAY);

        assertTrue(summary.enabled());
        assertTrue(summary.generated());
        assertEquals("FY2025/26-Q4", summary.period());

        RecruitmentEvent event = singleDigestEvent("AI_DIGEST_GENERATED");
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
        Map<String, Object> payload = parse(event.getPayload());
        assertEquals(AiDigestService.KIND_REJECTION_PATTERNS, payload.get("kind"));
        assertEquals("2026-04", payload.get("window_from"));
        assertEquals("2026-06", payload.get("window_to"));
        Map<String, Object> kpis = asMap(payload.get("kpis"));
        assertEquals(windowRejections, ((Number) kpis.get("rejections")).longValue());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(openAIService).generatePlainText(anyString(), prompt.capture(), anyString(),
                anyInt(), any(), anyBoolean());
        assertTrue(prompt.getValue().contains(sourceDim + ": 2 afslag af 5 ansøgninger"),
                "prompt must carry the per-source rejection rate");
        assertTrue(prompt.getValue().contains("PROFILE_MISMATCH"),
                "prompt must carry the reason-code distribution");
        assertFalse(prompt.getValue().contains(RecruitmentEventPiiAssertions.PII_SENTINEL));
    }

    @Test
    void rejectionDigest_outsideGraceWindow_noRetroactiveBackfill() {
        AiDigestService.DigestSummary summary = service.runRejectionPatterns(OUTSIDE_GRACE);
        assertTrue(summary.enabled());
        assertFalse(summary.generated());
        assertNull(summary.period());
        verifyNoInteractions(openAIService);
        assertEquals(0, digestEventCount("AI_DIGEST_GENERATED"));
    }

    @Test
    void rejectionDigest_idempotentPerQuarter() {
        assertTrue(service.runRejectionPatterns(REJECTION_TODAY).generated());
        assertFalse(service.runRejectionPatterns(REJECTION_TODAY).generated());
        verify(openAIService, times(1)).generatePlainText(anyString(), anyString(), anyString(),
                anyInt(), any(), anyBoolean());
    }

    // =========================================================================
    // Anonymization survival (plan §P24 DoD)
    // =========================================================================

    @Test
    void digest_survivesACandidateAnonymizationUnchanged() {
        assertTrue(service.runWeeklyFunnel(WEEKLY_TODAY).generated());
        RecruitmentEvent before = singleDigestEvent("AI_DIGEST_GENERATED");
        String payloadBefore = before.getPayload();

        // The REAL anonymizer on the sentinel-named candidate: digest events
        // carry no candidate leg, so nothing of theirs may be rewritten.
        anonymizer.anonymize(sentinelCandidateUuid,
                dk.trustworks.intranet.recruitmentservice.services
                        .RecruitmentAnonymizerService.Mode.ON_REQUEST,
                java.util.UUID.randomUUID().toString());

        RecruitmentEvent after = QuarkusTransaction.requiringNew().call(() ->
                RecruitmentEvent.findById(before.getSeq()));
        assertEquals(payloadBefore, after.getPayload(),
                "digest narrative + KPIs live in payload BY DESIGN and must survive anonymization");
        assertEquals(RecruitmentPiiState.NONE, after.getPiiState());
    }

    // =========================================================================
    // Period helpers (pure)
    // =========================================================================

    @Test
    void fiscalQuarterHelpers_followTheJulyJuneConvention() {
        assertEquals(YearMonth.of(2026, 7), AiDigestService.fiscalQuarterStart(YearMonth.of(2026, 7)));
        assertEquals(YearMonth.of(2026, 7), AiDigestService.fiscalQuarterStart(YearMonth.of(2026, 9)));
        assertEquals(YearMonth.of(2026, 4), AiDigestService.fiscalQuarterStart(YearMonth.of(2026, 6)));
        assertEquals(YearMonth.of(2025, 10), AiDigestService.fiscalQuarterStart(YearMonth.of(2025, 12)));

        assertEquals("FY2025/26-Q1", AiDigestService.fiscalQuarterLabel(YearMonth.of(2025, 7)));
        assertEquals("FY2025/26-Q2", AiDigestService.fiscalQuarterLabel(YearMonth.of(2025, 10)));
        assertEquals("FY2025/26-Q3", AiDigestService.fiscalQuarterLabel(YearMonth.of(2026, 1)));
        assertEquals("FY2025/26-Q4", AiDigestService.fiscalQuarterLabel(YearMonth.of(2026, 4)));

        assertEquals("2026-W30", AiDigestService.isoWeekKey(LocalDate.of(2026, 7, 20)));
        assertEquals("2026-W01", AiDigestService.isoWeekKey(LocalDate.of(2025, 12, 29)),
                "ISO week-based year: 29 Dec 2025 belongs to 2026-W01");
    }

    @Test
    void sanitizeNarrative_stripsControlCharsAndCaps() {
        assertNull(AiDigestService.sanitizeNarrative(null));
        assertNull(AiDigestService.sanitizeNarrative("   "));
        assertEquals("a b\nc", AiDigestService.sanitizeNarrative("ab\r\nc"));
        String longText = "x".repeat(AiDigestService.NARRATIVE_MAX_LENGTH + 500);
        assertEquals(AiDigestService.NARRATIVE_MAX_LENGTH,
                AiDigestService.sanitizeNarrative(longText).length());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Seed one fact row tagged with the fixture position dim (the cleanup key). */
    private void insertFact(String month, String fact, String source, String stageFrom,
                            String stageTo, String outcome, String detail, long cnt,
                            String sumDays) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_fact_monthly
                            (month, fact, position_uuid, practice_uuid, hiring_track, source,
                             stage_from, stage_to, outcome, detail, person_uuid, cnt, sum_days)
                        VALUES (:month, :fact, :position, '', '', :source,
                                :stageFrom, :stageTo, :outcome, :detail, '', :cnt, :sumDays)
                        """)
                .setParameter("month", month)
                .setParameter("fact", fact)
                .setParameter("position", positionDim)
                .setParameter("source", source)
                .setParameter("stageFrom", stageFrom)
                .setParameter("stageTo", stageTo)
                .setParameter("outcome", outcome)
                .setParameter("detail", detail)
                .setParameter("cnt", cnt)
                .setParameter("sumDays", new BigDecimal(sumDays))
                .executeUpdate();
    }

    private long windowSum(String fact, String from, String to) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                        "SELECT COALESCE(SUM(cnt), 0) FROM recruitment_fact_monthly "
                        + "WHERE fact = :fact AND month BETWEEN :f AND :t")
                .setParameter("fact", fact)
                .setParameter("f", from)
                .setParameter("t", to)
                .getSingleResult()).longValue());
    }

    private long windowRejections(String from, String to) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                        "SELECT COALESCE(SUM(cnt), 0) FROM recruitment_fact_monthly "
                        + "WHERE fact = 'TERMINAL' AND outcome = 'REJECTED' "
                        + "AND month BETWEEN :f AND :t")
                .setParameter("f", from)
                .setParameter("t", to)
                .getSingleResult()).longValue());
    }

    /** Digest events are always test artifacts on the local DB (feature is new). */
    private void deleteDigestEvents() {
        em.createNativeQuery("DELETE FROM recruitment_events "
                        + "WHERE event_type IN ('AI_DIGEST_GENERATED', 'DPO_DIGEST_SENT')")
                .executeUpdate();
    }

    private long digestEventCount(String type) {
        return QuarkusTransaction.requiringNew().call(() ->
                RecruitmentEvent.count("eventType", dk.trustworks.intranet.recruitmentservice
                        .events.RecruitmentEventType.valueOf(type)));
    }

    private RecruitmentEvent singleDigestEvent(String type) {
        List<RecruitmentEvent> events = QuarkusTransaction.requiringNew().call(() ->
                RecruitmentEvent.list("eventType", dk.trustworks.intranet.recruitmentservice
                        .events.RecruitmentEventType.valueOf(type)));
        assertEquals(1, events.size(), "exactly one digest event expected");
        return events.get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        assertTrue(value instanceof Map, "kpis must be a JSON object");
        return (Map<String, Object>) value;
    }

    private Map<String, Object> parse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
        } catch (Exception e) {
            throw new AssertionError("payload is not valid JSON", e);
        }
    }
}
