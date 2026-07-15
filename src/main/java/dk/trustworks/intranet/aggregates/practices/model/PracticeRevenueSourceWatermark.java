package dk.trustworks.intranet.aggregates.practices.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "practice_revenue_source_watermark")
public class PracticeRevenueSourceWatermark extends PanacheEntityBase {

    @Id
    @Column(name = "source_name")
    public String sourceName;

    @Column(name = "source_version", columnDefinition = "BIGINT UNSIGNED")
    public BigInteger sourceVersion;

    @Column(name = "source_state")
    public String sourceState;

    @Column(name = "attempt_token")
    public String attemptToken;

    @Column(name = "started_at")
    public LocalDateTime startedAt;

    @Column(name = "changed_at")
    public LocalDateTime changedAt;

    @Column(name = "completed_at")
    public LocalDateTime completedAt;

    @Column(name = "last_observed_at")
    public LocalDateTime lastObservedAt;

    @Column(name = "affected_start_month")
    public LocalDate affectedStartMonth;

    @Column(name = "affected_end_month")
    public LocalDate affectedEndMonth;

    @Column(name = "safe_reason")
    public String safeReason;

    @Column(name = "last_fact_change_log_id", columnDefinition = "BIGINT UNSIGNED")
    public BigInteger lastFactChangeLogId;

    @Column(name = "last_pruned_fact_change_log_id", columnDefinition = "BIGINT UNSIGNED")
    public BigInteger lastPrunedFactChangeLogId;

    @Column(name = "recovery_target_fact_change_log_id", columnDefinition = "BIGINT UNSIGNED")
    public BigInteger recoveryTargetFactChangeLogId;

    @Column(name = "recovery_token")
    public String recoveryToken;

    @Column(name = "recovery_started_at")
    public LocalDateTime recoveryStartedAt;

    @Column(name = "retention_gap_reason")
    public String retentionGapReason;

    // Long, not BigInteger: Hibernate accepts only int/Integer/short/Short/long/Long/Timestamp
    // as a @Version type and dies at boot otherwise.
    @Version
    @Column(name = "optimistic_version", columnDefinition = "BIGINT UNSIGNED")
    public Long optimisticVersion;
}
