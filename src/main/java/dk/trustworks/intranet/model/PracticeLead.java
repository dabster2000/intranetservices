package dk.trustworks.intranet.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.security.AuditEntityListener;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A temporal practice-lead assignment (V418). Mirrors the {@code teamroles}
 * idiom: multiple concurrent leads possible, history preserved via
 * {@code enddate = null} meaning "current". Dates serialize as ISO yyyy-MM-dd
 * to match the team-membership editing contract the frontend already uses.
 * Audit fields follow the house {@link Auditable} pattern (V421): populated by
 * {@link AuditEntityListener} from the X-Requested-By header.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "practice_lead")
@EntityListeners(AuditEntityListener.class)
public class PracticeLead extends PanacheEntityBase implements Auditable {

    @Id
    private String uuid;

    @Column(name = "practice_code")
    private String practiceCode;

    /**
     * Surrogate twin of {@link #practiceCode} (V424, Part 2 Phase 1). Since
     * Phase 3 the application maintains it ({@code PracticeService.startLead}
     * sets both identifiers together; the V424 backfill covered pre-existing
     * rows) and it serializes — lead responses carry code and uuid (§4.5).
     * Read-only on input: the practice identity comes from the route.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "practice_uuid")
    private String practiceUuid;

    private String useruuid;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate startdate;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate enddate;

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "modified_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String modifiedBy;

    public PracticeLead(String uuid, String practiceCode, String useruuid, LocalDate startdate, LocalDate enddate) {
        this.uuid = uuid;
        this.practiceCode = practiceCode;
        this.useruuid = useruuid;
        this.startdate = startdate;
        this.enddate = enddate;
    }
}
