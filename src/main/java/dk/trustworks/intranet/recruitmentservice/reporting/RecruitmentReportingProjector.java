package dk.trustworks.intranet.recruitmentservice.reporting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactor;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * P20 ReportingProjector (spec §3.2 read-model kind 3): folds the
 * recruitment event stream into the monthly-grain projection
 * {@code recruitment_fact_monthly} (V449). The projection is the input
 * for {@code /recruitment/reports} and — by design — the <em>sole</em>
 * input for the P24 AI digests (aggregates only, by construction).
 *
 * <h3>Anonymization-proof</h3>
 * Every increment is built from uuids, enum codes and timestamps; the
 * fact table has no column that could hold a name or free text.
 * {@code person_uuid} only ever carries EMPLOYEE uuids (interviewer,
 * referrer). Anonymizing a candidate therefore changes nothing here —
 * locked by {@code RecruitmentReportingReconciliationTest}.
 *
 * <h3>Deliberately not feature-flag gated</h3>
 * {@code recruitment.gdpr.enabled} gates the reports <em>surface</em>
 * (spec §11), not this bookkeeping: the projection has no external side
 * effects and must accumulate history from day one (and can always be
 * rebuilt, so the distinction is cosmetic anyway).
 *
 * <h3>Rebuild — the one sanctioned replay-from-history</h3>
 * {@link #rebuild()} resets the projection (facts + watermark + dedupe
 * claims) and re-drives the standard catch-up machinery from seq 0.
 * Safe because the handler has no side effects outside the fact table.
 * Events younger than the catch-up grace horizon are excluded from the
 * sweep by design and are reconciled by the live path / the scheduled
 * 5-minute catch-up batchlet shortly after.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentReportingProjector extends RecruitmentReactor {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    /** Rebuild loop backstop: 200 sweeps × 10k events/sweep = 2M events. */
    private static final int MAX_REBUILD_SWEEPS = 200;

    private static final String UPSERT = """
            INSERT INTO recruitment_fact_monthly
                (month, fact, position_uuid, practice_uuid, hiring_track, source,
                 stage_from, stage_to, outcome, detail, person_uuid, cnt, sum_days)
            VALUES (:month, :fact, :position, :practice, :track, :source,
                    :stageFrom, :stageTo, :outcome, :detail, :person, :cnt, :sumDays)
            ON DUPLICATE KEY UPDATE
                cnt = cnt + VALUES(cnt),
                sum_days = sum_days + VALUES(sum_days)
            """;

    @Inject
    ObjectMapper objectMapper;

    /** Own handle — the base class field is package-private to the events package. */
    @Inject
    EntityManager entityManager;

    @Override
    public String name() {
        return "reporting-projector";
    }

    @Override
    protected void handle(RecruitmentEvent event) {
        Map<String, Object> payload = parse(event.getPayload());
        switch (event.getEventType()) {
            case CANDIDATE_CREATED -> increment(event, ReportingFact.CANDIDATE_CREATED,
                    Dims.none().source(str(payload.get("source"))));

            case APPLICATION_CREATED -> increment(event, ReportingFact.APPLICATION_CREATED,
                    positionDims(event, payload)
                            .source(candidateSource(event))
                            .detail(str(payload.get("origin"))));

            case APPLICATION_STAGE_CHANGED -> increment(event, ReportingFact.STAGE_MOVED,
                    positionDims(event, payload)
                            .stageFrom(str(payload.get("from")))
                            .stageTo(str(payload.get("to")))
                            .outcome(str(payload.get("direction")))
                            .sumDays(daysInCurrentStage(event)));

            case APPLICATION_REJECTED -> increment(event, ReportingFact.TERMINAL,
                    positionDims(event, payload)
                            .source(candidateSource(event))
                            .stageFrom(str(payload.get("from_stage")))
                            .outcome("REJECTED")
                            .detail(str(payload.get("reason_code")))
                            .sumDays(daysInCurrentStage(event)));

            case APPLICATION_WITHDRAWN -> increment(event, ReportingFact.TERMINAL,
                    positionDims(event, payload)
                            .source(candidateSource(event))
                            .stageFrom(str(payload.get("from_stage")))
                            .outcome("WITHDRAWN")
                            .sumDays(daysInCurrentStage(event)));

            // Return-to-pool is the third terminal (findings §P4): the pool
            // event carries the application context; plain pooling (no
            // application subject / no terminal key) is not a funnel fact.
            case CANDIDATE_POOLED -> {
                String terminal = str(payload.get("terminal"));
                if (!terminal.isEmpty() && event.getApplicationUuid() != null) {
                    increment(event, ReportingFact.TERMINAL,
                            positionDims(event, payload)
                                    .source(candidateSource(event))
                                    .stageFrom(str(payload.get("from_stage")))
                                    .outcome(terminal)
                                    .sumDays(daysInCurrentStage(event)));
                }
            }

            case CANDIDATE_HIRED -> increment(event, ReportingFact.HIRED,
                    positionDims(event, payload)
                            .source(candidateSource(event))
                            .person(referrerOf(event)));

            case SCORECARD_SUBMITTED -> increment(event, ReportingFact.SCORECARD_SUBMITTED,
                    positionDims(event, payload)
                            .person(nullToEmpty(event.getActorUuid()))
                            .outcome(origin(payload)));

            case REFERRAL_SUBMITTED -> increment(event, ReportingFact.REFERRAL_SUBMITTED,
                    Dims.none()
                            .person(nullToEmpty(event.getActorUuid()))
                            .outcome(origin(payload)));

            case REFERRAL_TRIAGED -> increment(event, ReportingFact.REFERRAL_TRIAGED,
                    Dims.none()
                            .person(referralSubmitter(str(payload.get("referral_uuid")), event.getSeq()))
                            .outcome(str(payload.get("outcome")))
                            .detail(origin(payload)));

            case ART14_NOTICE_SENT -> increment(event, ReportingFact.ART14_NOTICE_SENT,
                    Dims.none().detail(str(payload.get("channel"))));

            case CONSENT_GRANTED -> increment(event, ReportingFact.CONSENT_GRANTED,
                    Dims.none().outcome(str(payload.get("kind"))));

            case CONSENT_WITHDRAWN -> increment(event, ReportingFact.CONSENT_WITHDRAWN,
                    Dims.none().outcome(str(payload.get("kind"))));

            case CONSENT_EXPIRED -> increment(event, ReportingFact.CONSENT_EXPIRED,
                    Dims.none().outcome(str(payload.get("kind"))));

            case CANDIDATE_ANONYMIZED -> increment(event, ReportingFact.ANONYMIZED,
                    Dims.none().outcome(str(payload.get("mode"))));

            case DSAR_RECEIVED -> increment(event, ReportingFact.DSAR_RECEIVED, Dims.none());

            case DSAR_EXPORTED -> increment(event, ReportingFact.DSAR_EXPORTED, Dims.none());

            default -> {
                // not a reporting fact — silent advance
            }
        }
    }

    // ------------------------------------------------------------------
    // Rebuild
    // ------------------------------------------------------------------

    /** Result of a full rebuild, for the admin endpoint response. */
    public record RebuildSummary(int sweeps, int eventsProjected, boolean blocked) {
    }

    /**
     * Reset the projection and replay the whole stream through the
     * standard reactor machinery (per-event dedupe claims + watermark),
     * so concurrent live deliveries stay exactly-once. Reset order
     * matters: the dedupe delete blocks on any in-flight delivery's row
     * lock, making it a barrier — that delivery's increment commits
     * first and is then wiped and replayed.
     */
    public RebuildSummary rebuild() {
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery("DELETE FROM recruitment_reactor_deliveries WHERE reactor_name = :name")
                    .setParameter("name", name()).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM recruitment_fact_monthly").executeUpdate();
            entityManager.createNativeQuery("INSERT INTO recruitment_reactor_offsets (reactor_name, last_processed_seq) " +
                            "VALUES (:name, 0) ON DUPLICATE KEY UPDATE last_processed_seq = 0")
                    .setParameter("name", name()).executeUpdate();
        });
        int sweeps = 0;
        int projected = 0;
        boolean blocked = false;
        for (; sweeps < MAX_REBUILD_SWEEPS; sweeps++) {
            CatchUpSummary summary = catchUp();
            projected += summary.handled();
            blocked = summary.blocked();
            if (blocked || summary.handled() == 0) {
                break;
            }
        }
        log.infof("Reporting projection rebuilt: %d events in %d sweep(s)%s",
                projected, sweeps + 1, blocked ? " — BLOCKED on a failing event" : "");
        return new RebuildSummary(sweeps + 1, projected, blocked);
    }

    // ------------------------------------------------------------------
    // Dimension resolution
    // ------------------------------------------------------------------

    /**
     * Position-anchored dims. Track comes from the payload when present
     * (stamped since P4); practice is resolved from the position row —
     * a later practice reassignment shifts history on the next rebuild,
     * which is the intended "registry is runtime-mutable" behavior.
     */
    private Dims positionDims(RecruitmentEvent event, Map<String, Object> payload) {
        Dims dims = Dims.none().position(nullToEmpty(event.getPositionUuid()));
        String track = str(payload.get("hiring_track"));
        if (event.getPositionUuid() != null) {
            RecruitmentPosition position = RecruitmentPosition.findById(event.getPositionUuid());
            if (position != null) {
                dims.practice(nullToEmpty(position.getPracticeUuid()));
                if (track.isEmpty() && position.getHiringTrack() != null) {
                    track = position.getHiringTrack().name();
                }
            }
        }
        return dims.track(track);
    }

    /** Candidate source survives anonymization (structural skeleton, spec §4.1). */
    private String candidateSource(RecruitmentEvent event) {
        if (event.getCandidateUuid() == null) {
            return "";
        }
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(event.getCandidateUuid());
        return candidate == null || candidate.getSource() == null ? "" : candidate.getSource().name();
    }

    /** Referrer employee uuid for the HIRED leaderboard column ('' when not referred). */
    private String referrerOf(RecruitmentEvent event) {
        if (event.getCandidateUuid() == null) {
            return "";
        }
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(event.getCandidateUuid());
        return candidate == null ? "" : nullToEmpty(candidate.getReferredByUserUuid());
    }

    /**
     * The referrer of a triaged referral = the actor of its
     * REFERRAL_SUBMITTED event, found in the stream itself (the referral
     * row's columns are anonymization targets; events are forever).
     */
    private String referralSubmitter(String referralUuid, long beforeSeq) {
        if (referralUuid.isEmpty()) {
            return "";
        }
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT actor_uuid FROM recruitment_events " +
                        "WHERE event_type = 'REFERRAL_SUBMITTED' AND seq < :seq " +
                        "AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.referral_uuid')) = :referral " +
                        "ORDER BY seq DESC LIMIT 1")
                .setParameter("seq", beforeSeq)
                .setParameter("referral", referralUuid)
                .getResultList();
        return rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0).toString();
    }

    /**
     * Fractional days the application spent in the stage it is leaving:
     * distance to the latest earlier stage-defining event. Pure function
     * of the immutable stream — identical on live projection and rebuild.
     */
    private BigDecimal daysInCurrentStage(RecruitmentEvent event) {
        if (event.getApplicationUuid() == null) {
            return BigDecimal.ZERO;
        }
        List<?> rows = entityManager.createQuery(
                        "SELECT MAX(e.occurredAt) FROM RecruitmentEvent e " +
                        "WHERE e.candidateUuid = :candidate AND e.applicationUuid = :application " +
                        "AND e.seq < :seq AND e.eventType IN ('APPLICATION_CREATED', 'APPLICATION_STAGE_CHANGED')")
                .setParameter("candidate", event.getCandidateUuid())
                .setParameter("application", event.getApplicationUuid())
                .setParameter("seq", event.getSeq())
                .getResultList();
        Object entered = rows.isEmpty() ? null : rows.get(0);
        if (!(entered instanceof LocalDateTime enteredAt)) {
            return BigDecimal.ZERO;
        }
        long minutes = Duration.between(enteredAt, event.getOccurredAt()).toMinutes();
        if (minutes <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(1440), 2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------
    // Upsert
    // ------------------------------------------------------------------

    private void increment(RecruitmentEvent event, ReportingFact fact, Dims dims) {
        LocalDate month = event.getOccurredAt().toLocalDate().withDayOfMonth(1);
        entityManager.createNativeQuery(UPSERT)
                .setParameter("month", month)
                .setParameter("fact", fact.name())
                .setParameter("position", dims.position)
                .setParameter("practice", dims.practice)
                .setParameter("track", dims.track)
                .setParameter("source", dims.source)
                .setParameter("stageFrom", dims.stageFrom)
                .setParameter("stageTo", dims.stageTo)
                .setParameter("outcome", dims.outcome)
                .setParameter("detail", dims.detail)
                .setParameter("person", dims.person)
                .setParameter("cnt", 1L)
                .setParameter("sumDays", dims.sumDays)
                .executeUpdate();
    }

    /** Mutable dim collector; '' sentinels per the V449 contract. */
    private static final class Dims {
        String position = "";
        String practice = "";
        String track = "";
        String source = "";
        String stageFrom = "";
        String stageTo = "";
        String outcome = "";
        String detail = "";
        String person = "";
        BigDecimal sumDays = BigDecimal.ZERO;

        static Dims none() {
            return new Dims();
        }

        Dims position(String v) {
            this.position = v;
            return this;
        }

        Dims practice(String v) {
            this.practice = v;
            return this;
        }

        Dims track(String v) {
            this.track = v;
            return this;
        }

        Dims source(String v) {
            this.source = v;
            return this;
        }

        Dims stageFrom(String v) {
            this.stageFrom = v;
            return this;
        }

        Dims stageTo(String v) {
            this.stageTo = v;
            return this;
        }

        Dims outcome(String v) {
            this.outcome = v;
            return this;
        }

        Dims detail(String v) {
            this.detail = v;
            return this;
        }

        Dims person(String v) {
            this.person = v;
            return this;
        }

        Dims sumDays(BigDecimal v) {
            this.sumDays = v;
            return this;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Scorecards/referrals stamp origin since P14/P18; older events count as web (findings §P18). */
    private static String origin(Map<String, Object> payload) {
        String origin = str(payload.get("origin"));
        return origin.isEmpty() ? "web" : origin;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
