package dk.trustworks.intranet.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Practice registry row (V418). The {@code code} is the legacy storage key
 * (PM/BA/SA/DEV/CYB/UD/JK); {@code displayCode} is the user-facing short code
 * (PM/IA/BU/TECH/CYB). {@code type} is stored as the ENUM('PRACTICE','SEGMENT')
 * mapped to a plain String — the practice pickers filter on {@code type = 'PRACTICE'}.
 * Audit fields follow the house {@link Auditable} pattern (V421): populated by
 * {@link AuditEntityListener} from the X-Requested-By header.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "practice")
@EntityListeners(AuditEntityListener.class)
public class Practice extends PanacheEntityBase implements Auditable {

    @Id
    private String code;

    /**
     * Stable surrogate identity (V424, Part 2 Phase 1). {@code code} stays the PK
     * until Phase 5; this uuid is a UNIQUE attribute. Migrations mint it for
     * existing rows; {@link dk.trustworks.intranet.services.PracticeService#create}
     * sets it on new rows (hence insertable). Serialized since Phase 3 (§4.5):
     * the uuid is the canonical API identifier and {@code GET /practices} is the
     * bootstrap call carrying it to every practice-aware UI. Read-only on input —
     * identity is server-minted, never client-assigned.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "uuid")
    private String uuid;

    @Column(name = "display_code")
    private String displayCode;

    private String name;

    private String type;

    private boolean active;

    @Column(name = "sort_order")
    private int sortOrder;

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

    public Practice(String code, String displayCode, String name, String type, boolean active, int sortOrder) {
        this.code = code;
        this.displayCode = displayCode;
        this.name = name;
        this.type = type;
        this.active = active;
        this.sortOrder = sortOrder;
    }
}
