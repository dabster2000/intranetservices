package dk.trustworks.intranet.recruitmentservice.domain.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Candidate CV row backed by table {@code recruitment_candidate_cv} (V307).
 *
 * <p>The {@code current_for_unique} column is derived by triggers
 * {@code trg_cv_bi} and {@code trg_cv_bu}: it equals {@code candidate_uuid}
 * when {@code is_current = 1}, and NULL otherwise. The unique index
 * {@code uk_one_current_cv_per_candidate} on {@code current_for_unique}
 * therefore enforces "at most one current CV per candidate" while
 * permitting many non-current CVs (NULL rows are excluded from the
 * unique check). See {@code handoff-notes.md} for the MariaDB-10
 * generated-column trigger workaround rationale.</p>
 */
@Entity
@Table(name = "recruitment_candidate_cv")
public class CandidateCv extends PanacheEntityBase {

    @Id
    @Column(length = 36)
    public String uuid;

    @Column(name = "candidate_uuid", length = 36, nullable = false)
    public String candidateUuid;

    @Column(name = "file_url", length = 1024, nullable = false)
    public String fileUrl;

    @Column(name = "file_sha256", length = 64, nullable = false)
    public String fileSha256;

    @Column(name = "is_current", nullable = false)
    public boolean isCurrent;

    /**
     * Trigger-derived; do NOT write from JPA. See class Javadoc and
     * {@code handoff-notes.md} (V307 trigger workaround).
     */
    @Column(name = "current_for_unique", length = 36, insertable = false, updatable = false)
    public String currentForUnique;

    @Column(name = "uploaded_by_uuid", length = 36, nullable = false)
    public String uploadedByUuid;

    @Column(name = "uploaded_at", nullable = false)
    public LocalDateTime uploadedAt;

    @Column(name = "extraction_artifact_uuid", length = 36)
    public String extractionArtifactUuid;
}
