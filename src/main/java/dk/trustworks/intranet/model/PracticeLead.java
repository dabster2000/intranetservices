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
import org.hibernate.annotations.Formula;

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

    /**
     * Practice code, DERIVED from {@link #practiceUuid} via the registry
     * (Phase 5A) — the {@code practice_lead.practice_code} column is no longer
     * mapped and is dropped by V428. Lead responses keep carrying both the
     * code (this derived field) and the uuid (§4.5).
     */
    @Formula("(select prc.code from practice prc where prc.uuid = practice_uuid)")
    private String practiceCode;

    /**
     * The lead's practice identity (V424; sole persisted key since Phase 5A —
     * {@code PracticeService.startLead} writes only this column) and part of
     * the lead payload (§4.5). Read-only on input: the practice identity
     * comes from the route.
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

    /**
     * @param practiceUuid the registry uuid — the persisted practice key
     * @param practiceCode the registry code, set in memory only (the field is
     *                     a formula) so the create response carries it without
     *                     a reload
     */
    public PracticeLead(String uuid, String practiceUuid, String practiceCode,
                        String useruuid, LocalDate startdate, LocalDate enddate) {
        this.uuid = uuid;
        this.practiceUuid = practiceUuid;
        this.practiceCode = practiceCode;
        this.useruuid = useruuid;
        this.startdate = startdate;
        this.enddate = enddate;
    }
}
