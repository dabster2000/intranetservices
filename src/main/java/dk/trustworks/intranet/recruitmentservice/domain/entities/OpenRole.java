package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.*;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "recruitment_open_role")
public class OpenRole extends PanacheEntityBase {

    @Id
    @Column(length = 36)
    public String uuid;

    @Column(nullable = false)
    public String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "hiring_category", length = 40, nullable = false)
    public HiringCategory hiringCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "pipeline_kind", length = 20, nullable = false)
    public PipelineKind pipelineKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "practice", length = 10)
    public Practice practice;

    @Column(name = "career_level_uuid", length = 36)
    public String careerLevelUuid;

    @Column(name = "company_uuid", length = 36)
    public String companyUuid;

    @Column(name = "team_uuid", length = 36, nullable = false)
    public String teamUuid;

    @Column(name = "function_area", length = 120)
    public String functionArea;

    @Enumerated(EnumType.STRING)
    @Column(name = "hiring_source", length = 40, nullable = false)
    public HiringSource hiringSource;

    @Column(name = "hiring_reason", columnDefinition = "TEXT")
    public String hiringReason;

    @Column(name = "target_start_date")
    public LocalDate targetStartDate;

    @Column(name = "expected_allocation", precision = 4, scale = 2)
    public java.math.BigDecimal expectedAllocation;

    @Column(name = "expected_rate_band", length = 40)
    public String expectedRateBand;

    @Column(name = "salary_min")
    public Integer salaryMin;

    @Column(name = "salary_max")
    public Integer salaryMax;

    @Column(length = 3)
    public String currency = "DKK";

    public Integer priority;

    @Enumerated(EnumType.STRING)
    @Column(length = 40, nullable = false)
    public RoleStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "advertising_status", length = 20, nullable = false)
    public WorkstreamStatus advertisingStatus = WorkstreamStatus.NOT_STARTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "search_status", length = 20, nullable = false)
    public WorkstreamStatus searchStatus = WorkstreamStatus.NOT_STARTED;

    @Column(name = "created_by_uuid", length = 36, nullable = false)
    public String createdByUuid;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt = LocalDateTime.now();

    public OpenRole() {}

    public static OpenRole withFreshUuid() {
        OpenRole r = new OpenRole();
        r.uuid = UUID.randomUUID().toString();
        return r;
    }
}
