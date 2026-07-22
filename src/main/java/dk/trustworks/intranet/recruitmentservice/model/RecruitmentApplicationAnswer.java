package dk.trustworks.intranet.recruitmentservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One long-form answer the candidate gave on an application form, keyed by
 * a stable question code (ATS spec §4.1: {@code WHY_TRUSTWORKS},
 * {@code BEST_TASKS}, {@code DNA_MATCH}, {@code STRENGTHS}, …).
 * <p>
 * This is the deliberate state-table PII exception (spec §4.1 design note):
 * the candidate's own words need structured display on the P8 Application
 * tab, so they live here rather than in event {@code pii} blocks — making
 * this table a named anonymization target for the P19 engine.
 * <p>
 * Two ownership shapes since V437 (P5): a position-form answer is scoped
 * to its {@link #applicationUuid}; an unsolicited-form answer has no
 * application (recruiter triage attaches one later — deliberate P5 spec
 * decision) and scopes to its {@link #candidateUuid}. Exactly one of the
 * two is set — {@code chk_raa_owner} enforces the XOR in the database.
 * Rows are written by the P5 public forms (and, later, the Airtable
 * importer) and read by the P8 profile.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_application_answers")
public class RecruitmentApplicationAnswer extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /**
     * FK to {@code recruitment_applications.uuid} — set for position-form
     * answers, {@code NULL} for unsolicited (candidate-scoped) answers.
     */
    @Column(name = "application_uuid", length = 36, updatable = false)
    private String applicationUuid;

    /**
     * FK to {@code recruitment_candidates.uuid} — set for unsolicited
     * answers, {@code NULL} for application-scoped answers.
     */
    @Column(name = "candidate_uuid", length = 36, updatable = false)
    private String candidateUuid;

    /** Stable question code — display/reporting never interpret wording. */
    @Column(name = "question_key", length = 40, nullable = false, updatable = false)
    private String questionKey;

    /** PII. The candidate's own words — scrubbed by the P19 anonymizer. */
    @Column(name = "answer", columnDefinition = "TEXT")
    private String answer;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
