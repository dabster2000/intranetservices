package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactor;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidateFactState;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFactStates.FactNote;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFactStates.Persisted;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.util.Map;

/**
 * Maintains the {@code recruitment_candidate_fact_state} read model (V535)
 * from {@code NOTE_ADDED} events carrying a fact field — the board's
 * completeness rings read this instead of folding the event stream per
 * card (Interview Room design spec 2026-08-26 §4.3).
 * <p>
 * Pure bookkeeping, no external side effects, so like the reporting
 * projector it is deliberately NOT feature-flag gated: the projection must
 * accumulate from day one and can always be rebuilt. The state transition
 * itself lives in {@link RecruitmentFactStates} — shared with the
 * per-candidate derivation so the two can never disagree. Idempotent by
 * seq guard: replaying an event the row has already seen changes nothing.
 * <p>
 * Holds no prose (the fact VALUE lives in the event's pii and never
 * reaches this table), so anonymisation does not touch it (spec §4.4).
 */
@JBossLog
@ApplicationScoped
public class RecruitmentFactStateProjector extends RecruitmentReactor {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    /** Rebuild loop backstop — same bound as the reporting projector. */
    private static final int MAX_REBUILD_SWEEPS = 200;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EntityManager entityManager;

    @Override
    public String name() {
        return "fact-state-projector";
    }

    @Override
    protected void handle(RecruitmentEvent event) {
        if (event.getEventType() != RecruitmentEventType.NOTE_ADDED
                || event.getCandidateUuid() == null) {
            return;
        }
        Map<String, Object> payload = parse(event.getPayload());
        String field = payload.get("field") instanceof String s ? s : null;
        if (!RecruitmentFactVocabulary.isKnown(field)) {
            return;
        }
        FactNote note = new FactNote(
                event.getSeq(), field, event.getOccurredAt(),
                "ASKED".equals(payload.get("outcome")),
                Boolean.TRUE.equals(payload.get("confirmed")));

        RecruitmentCandidateFactState.Key key =
                new RecruitmentCandidateFactState.Key(event.getCandidateUuid(), field);
        RecruitmentCandidateFactState row =
                entityManager.find(RecruitmentCandidateFactState.class, key);
        if (row != null && note.seq() <= row.getLastValueEventSeq()) {
            return; // replay / out-of-order live delivery — already folded in
        }
        Persisted existing = row == null ? null
                : new Persisted(row.getState(), row.getLastValueEventSeq(), row.getLastStatedAt());
        Persisted next = RecruitmentFactStates.apply(existing, note);
        if (row == null) {
            row = new RecruitmentCandidateFactState();
            row.setCandidateUuid(event.getCandidateUuid());
            row.setField(field);
            applyTo(row, next);
            entityManager.persist(row);
        } else {
            applyTo(row, next);
        }
    }

    private static void applyTo(RecruitmentCandidateFactState row, Persisted value) {
        row.setState(value.state());
        row.setLastValueEventSeq(value.lastValueEventSeq());
        row.setLastStatedAt(value.lastStatedAt());
    }

    /** Result of a projection rebuild, for logs and the ops surface. */
    public record RebuildSummary(int sweeps, int projected, boolean blocked) {
    }

    /**
     * Reset the projection and replay the stream through the standard
     * catch-up machinery — the reporting projector's sanctioned
     * replay-from-history idiom. Safe: the handler writes only this table.
     */
    public RebuildSummary rebuild() {
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery(
                            "DELETE FROM recruitment_reactor_deliveries WHERE reactor_name = :name")
                    .setParameter("name", name()).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM recruitment_candidate_fact_state")
                    .executeUpdate();
            entityManager.createNativeQuery(
                            "INSERT INTO recruitment_reactor_offsets (reactor_name, last_processed_seq) "
                                    + "VALUES (:name, 0) ON DUPLICATE KEY UPDATE last_processed_seq = 0")
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
        log.infof("Fact-state projection rebuilt: %d events in %d sweep(s)%s",
                projected, sweeps + 1, blocked ? " — BLOCKED on a failing event" : "");
        return new RebuildSummary(sweeps + 1, projected, blocked);
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            log.warn("Unparseable NOTE_ADDED payload in fact-state projector — ignoring event");
            return Map.of();
        }
    }
}
