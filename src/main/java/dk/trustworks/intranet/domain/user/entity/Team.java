package dk.trustworks.intranet.domain.user.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "team")
@NoArgsConstructor
// Only dirty columns are written on flush — an unrelated managed-Team flush
// must not revert a concurrent practice_code/practice_uuid write (see User).
@DynamicUpdate
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
     * Surrogate twin of {@link #practiceCode} (V424, Part 2 Phase 1). Since
     * Phase 2, PracticeSyncService.applyTeamPracticeChange is the writer and
     * always sets both columns together (the application owns the twin
     * invariant — V426 dropped the trigger mirror on {@code user}, and the team
     * columns never had one). Not serialized yet; no read path consumes it
     * until Phase 3.
     */
    @JsonIgnore
    @Column(name = "practice_uuid")
    private String practiceUuid;

}
