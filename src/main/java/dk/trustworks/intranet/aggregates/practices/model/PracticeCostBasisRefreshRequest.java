package dk.trustworks.intranet.aggregates.practices.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "practice_cost_basis_refresh_request")
public class PracticeCostBasisRefreshRequest extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id", columnDefinition = "BIGINT UNSIGNED")
    public BigInteger requestId;

    @Column(name = "request_key")
    public String requestKey;
    public String cause;

    @Column(name = "trigger_origin")
    public String triggerOrigin;

    @Column(name = "cause_input_version", columnDefinition = "BIGINT UNSIGNED")
    public BigInteger causeInputVersion;

    @Column(name = "expected_full_refresh_version", columnDefinition = "BIGINT UNSIGNED")
    public BigInteger expectedFullRefreshVersion;

    @Column(name = "expected_incremental_refresh_version", columnDefinition = "BIGINT UNSIGNED")
    public BigInteger expectedIncrementalRefreshVersion;

    @Column(name = "expected_practice_basis_input_version", columnDefinition = "BIGINT UNSIGNED")
    public BigInteger expectedPracticeBasisInputVersion;

    @Column(name = "expected_finance_gl_version", columnDefinition = "BIGINT UNSIGNED")
    public BigInteger expectedFinanceGlVersion;

    @Column(name = "expected_account_classification_version", columnDefinition = "BIGINT UNSIGNED")
    public BigInteger expectedAccountClassificationVersion;

    @Column(name = "input_vector_fingerprint")
    public String inputVectorFingerprint;

    @Column(name = "dependency_fingerprint")
    public String dependencyFingerprint;

    @Column(name = "affected_start_date")
    public LocalDate affectedStartDate;

    @Column(name = "affected_end_date")
    public LocalDate affectedEndDate;

    public String status;

    @Column(name = "superseded_by_request_id", columnDefinition = "BIGINT UNSIGNED")
    public BigInteger supersededByRequestId;

    @Column(name = "owner_token")
    public String ownerToken;

    @Column(name = "attempt_count")
    public int attemptCount;

    @Column(name = "resulting_cost_generation_at")
    public LocalDateTime resultingCostGenerationAt;

    @Column(name = "resulting_basis_generation_id")
    public String resultingBasisGenerationId;

    @Column(name = "compared_cost_generation_at")
    public LocalDateTime comparedCostGenerationAt;

    @Column(name = "compared_basis_generation_id")
    public String comparedBasisGenerationId;

    @Column(name = "content_fingerprint")
    public String contentFingerprint;

    @Column(name = "safe_reason")
    public String safeReason;

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    @Column(name = "claimed_at")
    public LocalDateTime claimedAt;

    @Column(name = "completed_at")
    public LocalDateTime completedAt;

    @Column(name = "failed_at")
    public LocalDateTime failedAt;

    // Long, not BigInteger: Hibernate accepts only int/Integer/short/Short/long/Long/Timestamp
    // as a @Version type and dies at boot otherwise.
    @Version
    @Column(name = "optimistic_version", columnDefinition = "BIGINT UNSIGNED")
    public Long optimisticVersion;
}
