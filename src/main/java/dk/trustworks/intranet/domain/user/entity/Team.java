package dk.trustworks.intranet.domain.user.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "team")
@NoArgsConstructor
public class Team extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;
    private String name;
    private String shortname;
    private String description;
    private String logouuid;
    private boolean teamleadbonus;
    private boolean teambonus;

    /** Optional link to the practice registry (V418). NULL = no practice. */
    @Column(name = "practice_code")
    private String practiceCode;

    /**
     * Surrogate twin of {@link #practiceCode} (V424, Part 2 Phase 1). Written only
     * by the migration backfill this phase (insertable/updatable false); Phase 2's
     * PracticeSyncService becomes its writer. Not serialized yet (byte-identical
     * team payload); no read path consumes it until Phase 3.
     */
    @JsonIgnore
    @Column(name = "practice_uuid", insertable = false, updatable = false)
    private String practiceUuid;

}
