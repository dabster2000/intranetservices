package dk.trustworks.intranet.aggregates.practices.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "practice_basis_generation")
public class PracticeBasisGeneration extends PanacheEntityBase {

    @Id
    @Column(name = "generation_id", length = 36)
    public String generationId;

    public String status;

    @Column(name = "coverage_start_date")
    public LocalDate coverageStartDate;

    @Column(name = "coverage_end_date")
    public LocalDate coverageEndDate;

    @Column(name = "history_coverage_start_date")
    public LocalDate historyCoverageStartDate;

    @Column(name = "fallback_policy_version")
    public String fallbackPolicyVersion;

    @Column(name = "consultant_type_policy_version")
    public String consultantTypePolicyVersion;

    @Column(name = "full_refresh_version", columnDefinition = "BIGINT UNSIGNED")
    public BigInteger fullRefreshVersion;

    @Column(name = "incremental_refresh_version", columnDefinition = "BIGINT UNSIGNED")
    public BigInteger incrementalRefreshVersion;

    @Column(name = "practice_basis_input_source_version", columnDefinition = "BIGINT UNSIGNED")
    public BigInteger practiceBasisInputSourceVersion;

    @Column(name = "source_fingerprint", length = 64)
    public String sourceFingerprint;

    @Column(name = "capacity_source_fingerprint", length = 64)
    public String capacitySourceFingerprint;

    @Column(name = "dependency_manifest_fingerprint", length = 64)
    public String dependencyManifestFingerprint;

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    @Column(name = "published_at")
    public LocalDateTime publishedAt;

    @Column(name = "failure_code")
    public String failureCode;
}
