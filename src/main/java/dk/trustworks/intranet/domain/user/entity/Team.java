package dk.trustworks.intranet.domain.user.entity;

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

}
