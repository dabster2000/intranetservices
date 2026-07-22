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
 * Rows are written by the P5 public form (and, later, the Airtable
 * importer) and read by the P8 profile; P4 only establishes the table.
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

    /** FK to {@code recruitment_applications.uuid}. */
    @Column(name = "application_uuid", length = 36, nullable = false, updatable = false)
    private String applicationUuid;

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
