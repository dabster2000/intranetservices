package dk.trustworks.intranet.aggregates.practices.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@IdClass(PracticeUserEffectiveBasis.Key.class)
@Table(name = "practice_user_effective_basis_mat")
public class PracticeUserEffectiveBasis extends PanacheEntityBase {

    @Id
    @Column(name = "generation_id")
    public String generationId;

    @Id
    @JsonIgnore
    @Column(name = "user_uuid")
    public String userUuid;

    @Id
    @Column(name = "effective_from_date")
    public LocalDate effectiveFromDate;

    @Column(name = "effective_to_date_exclusive")
    public LocalDate effectiveToDateExclusive;

    @Column(name = "consultant_type")
    public String consultantType;

    @Column(name = "practice_code")
    public String practiceCode;

    @Column(name = "attribution_basis")
    public String attributionBasis;

    @Column(name = "fallback_reason")
    public String fallbackReason;

    @Column(name = "source_evidence")
    public String sourceEvidence;

    @Column(name = "source_fingerprint")
    public String sourceFingerprint;

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        public String generationId;
        public String userUuid;
        public LocalDate effectiveFromDate;
    }
}
