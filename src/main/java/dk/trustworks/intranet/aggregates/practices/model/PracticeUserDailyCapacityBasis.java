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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@IdClass(PracticeUserDailyCapacityBasis.Key.class)
@Table(name = "practice_user_daily_capacity_basis_mat")
public class PracticeUserDailyCapacityBasis extends PanacheEntityBase {

    @Id
    @Column(name = "generation_id")
    public String generationId;

    @Id
    @JsonIgnore
    @Column(name = "user_uuid")
    public String userUuid;

    @Id
    @Column(name = "capacity_date")
    public LocalDate capacityDate;

    @Column(name = "company_uuid")
    public String companyUuid;

    @Column(name = "gross_available_hours", precision = 24, scale = 6)
    public BigDecimal grossAvailableHours;

    @Column(name = "effective_basis_from_date")
    public LocalDate effectiveBasisFromDate;

    @Column(name = "consultant_type")
    public String consultantType;

    @Column(name = "practice_code")
    public String practiceCode;

    @Column(name = "capacity_source")
    public String capacitySource;

    @Column(name = "capacity_source_fingerprint")
    public String capacitySourceFingerprint;

    @Column(name = "historical_practice_fallback")
    public boolean historicalPracticeFallback;

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        public String generationId;
        public String userUuid;
        public LocalDate capacityDate;
    }
}
