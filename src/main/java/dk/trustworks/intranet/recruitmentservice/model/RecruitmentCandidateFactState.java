package dk.trustworks.intranet.recruitmentservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One row of the fact-state read model (Interview Room design spec
 * 2026-08-26 §4.3, migration V535): the newest persisted state of one
 * fact field for one candidate, maintained by
 * {@code RecruitmentFactStateProjector} on {@code NOTE_ADDED}.
 * <p>
 * A CACHE, never a source of truth: rebuildable from the event stream;
 * {@code UNKNOWN} is the absent row and {@code STALE} is derived at read
 * time from {@link #lastStatedAt} plus the group's freshness window —
 * a state that changes by the calendar cannot live in a column. Holds no
 * prose (field key, enum, seq, timestamp), so it is deliberately NOT an
 * anonymisation target (spec §4.4) and is never read by the anonymiser.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_candidate_fact_state")
@IdClass(RecruitmentCandidateFactState.Key.class)
public class RecruitmentCandidateFactState extends PanacheEntityBase {

    /** Persisted states — UNKNOWN and STALE are derived, never stored. */
    public static final String STATE_ASKED = "ASKED";
    public static final String STATE_STATED = "STATED";
    public static final String STATE_CONFIRMED = "CONFIRMED";

    @Id
    @Column(name = "candidate_uuid", length = 36, nullable = false)
    private String candidateUuid;

    /** {@code RecruitmentFactVocabulary} key. */
    @Id
    @Column(name = "field", length = 50, nullable = false)
    private String field;

    /** {@link #STATE_ASKED} | {@link #STATE_STATED} | {@link #STATE_CONFIRMED}. */
    @Column(name = "state", length = 12, nullable = false)
    private String state;

    /** {@code recruitment_events.seq} of the newest note carrying this field. */
    @Column(name = "last_value_event_seq", nullable = false)
    private long lastValueEventSeq;

    /** UTC {@code occurred_at} of the newest value-bearing note; null while only ASKED. */
    @Column(name = "last_stated_at")
    private LocalDateTime lastStatedAt;

    /** Composite key: (candidate, field). */
    public static class Key implements Serializable {
        private String candidateUuid;
        private String field;

        public Key() {
        }

        public Key(String candidateUuid, String field) {
            this.candidateUuid = candidateUuid;
            this.field = field;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(candidateUuid, other.candidateUuid)
                    && Objects.equals(field, other.field);
        }

        @Override
        public int hashCode() {
            return Objects.hash(candidateUuid, field);
        }
    }
}
