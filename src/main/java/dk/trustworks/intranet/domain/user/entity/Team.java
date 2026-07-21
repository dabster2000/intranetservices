package dk.trustworks.intranet.domain.user.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Formula;

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

    /**
     * Practice code, DERIVED from {@link #practiceUuid} via the registry
     * (Phase 5A) — the {@code team.practice_code} column is no longer mapped
     * and is dropped by V428. The JSON field {@code practiceCode} keeps its
     * wire shape (code string, or null for a practice-less team).
     */
    @Formula("(select prc.code from practice prc where prc.uuid = practice_uuid)")
    private String practiceCode;

    /**
     * The team's practice identity (V424; sole persisted key since Phase 5A).
     * PracticeSyncService.applyTeamPracticeChange is the only writer. Not
     * serialized — the wire carries the derived {@link #practiceCode}.
     */
    @JsonIgnore
    @Column(name = "practice_uuid")
    private String practiceUuid;

}
