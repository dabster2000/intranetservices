package dk.trustworks.intranet.model;

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
 * {@code createdAt}/{@code updatedAt} are DB-managed (defaults + ON UPDATE).
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "practice")
public class Practice extends PanacheEntityBase {

    @Id
    private String code;

    @Column(name = "display_code")
    private String displayCode;

    private String name;

    private String type;

    private boolean active;

    @Column(name = "sort_order")
    private int sortOrder;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public Practice(String code, String displayCode, String name, String type, boolean active, int sortOrder) {
        this.code = code;
        this.displayCode = displayCode;
        this.name = name;
        this.type = type;
        this.active = active;
        this.sortOrder = sortOrder;
    }
}
