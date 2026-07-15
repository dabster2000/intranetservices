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
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "practice_contribution_publication_control")
public class PracticeContributionPublicationControl extends PanacheEntityBase {

    @Id
    @Column(name = "control_id")
    public short controlId;

    @Column(name = "refresh_enabled")
    public boolean refreshEnabled;

    @Column(name = "contribution_serving_enabled")
    public boolean contributionServingEnabled;

    @Column(name = "legacy_cost_serving_enabled")
    public boolean legacyCostServingEnabled;

    // Long, not BigInteger: Hibernate accepts only int/Integer/short/Short/long/Long/Timestamp
    // as a @Version type and dies at boot otherwise.
    @Version
    @Column(name = "control_version", columnDefinition = "BIGINT UNSIGNED")
    public Long controlVersion;

    @Column(name = "last_transition_actor")
    public String lastTransitionActor;

    @Column(name = "last_transition_at")
    public LocalDateTime lastTransitionAt;

    @Column(name = "last_transition_reason")
    public String lastTransitionReason;

    @Column(name = "revenue_recovery_execution_id")
    public String revenueRecoveryExecutionId;

    @Column(name = "revenue_recovery_owner_token")
    public String revenueRecoveryOwnerToken;

    @Column(name = "revenue_recovery_started_at")
    public LocalDateTime revenueRecoveryStartedAt;

    @Column(name = "revenue_recovery_state")
    public String revenueRecoveryState;

    @Column(name = "recovery_expected_cost_generation_at")
    public LocalDateTime recoveryExpectedCostGenerationAt;

    @Column(name = "recovery_expected_cost_request_id", columnDefinition = "BIGINT UNSIGNED")
    public BigInteger recoveryExpectedCostRequestId;

    @Column(name = "recovery_expected_cost_request_key")
    public String recoveryExpectedCostRequestKey;

    @Column(name = "recovery_expected_cost_input_vector_fingerprint")
    public String recoveryExpectedCostInputVectorFingerprint;

    @Column(name = "recovery_expected_full_refresh_version", columnDefinition = "BIGINT UNSIGNED")
    public BigInteger recoveryExpectedFullRefreshVersion;

    @Column(name = "recovery_expected_source_vector_fingerprint")
    public String recoveryExpectedSourceVectorFingerprint;
}
