package dk.trustworks.intranet.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Practice registry row (V418). Since V429 the storage {@code code} IS the
 * canonical short code (PM/IA/BU/TECH/CYB); {@code displayCode} and {@code type}
 * survive on the wire as DERIVED getters only ({@link #getDisplayCode()} ≡ code,
 * {@link #getType()} ≡ "PRACTICE") — the backing columns stop being read here
 * and are physically dropped by V432. The registry holds nothing but practices
 * since V429 deleted the UD row, the only SEGMENT.
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

    private String name;

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

    public Practice(String code, String name, boolean active, int sortOrder) {
        this.code = code;
        this.name = name;
        this.active = active;
        this.sortOrder = sortOrder;
    }

    /**
     * LIVE WIRE FIELD, derived since V431: the frontend renders it in the admin
     * settings grid, sales-overview chips, notification config and allocation
     * legend, so it must keep appearing in JSON — but it is the storage code,
     * which has been the canonical display code on every row since the V429
     * fold. The {@code display_code} column is unread from here on and dropped
     * by V432. Getter-only: Jackson serializes it and ignores it on input.
     */
    public String getDisplayCode() {
        return code;
    }

    /**
     * LIVE WIRE FIELD, derived since V431: the frontend splits registry rows on
     * it ({@code PracticeAdminSettings}, {@code usePractices}), so it must keep
     * appearing in JSON — but V429 deleted the UD row, the only SEGMENT, so
     * every registry row is a practice and the answer is a constant. The
     * {@code type} column is unread from here on and dropped by V432.
     */
    public String getType() {
        return "PRACTICE";
    }
}
