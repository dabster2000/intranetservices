package dk.trustworks.intranet.aggregates.crm.plan.model;

import dk.trustworks.intranet.aggregates.crm.plan.model.enums.RelationRole;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.RelationSource;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One Trustworks person's relationship with one stakeholder, rated 0-4 now and 0-4 as a
 * target.
 *
 * <p><b>A rating records who made it and when</b> ({@link #assessedBy}, {@link #assessedAt})
 * — rule 5 of the account-plan data model. An unattributed "4" is an opinion pretending to
 * be a fact, and a rating from eighteen months ago is not the same claim as one from last
 * week. The tab shows a rating older than 180 days as stale for that reason.
 *
 * <p>The columns are {@code current_level} and {@code target_level}, not {@code current}
 * and {@code target}: {@code CURRENT} is a MariaDB reserved word and an unquoted one fails
 * CREATE TABLE at parse time, which is how V534's {@code lines} column took down the
 * staging canary.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "client_plan_relation")
public class ClientPlanRelation extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "stakeholder_uuid", length = 36, nullable = false)
    private String stakeholderUuid;

    @Column(name = "user_uuid", length = 36, nullable = false)
    private String userUuid;

    /** 0-4, how strong the relationship is today. */
    @Column(name = "current_level", nullable = false)
    private int currentLevel;

    /** 0-4, how strong it needs to be for the plan to work. */
    @Column(name = "target_level", nullable = false)
    private int targetLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 15, nullable = false)
    private RelationRole role;

    @Column(name = "last_interaction")
    private LocalDate lastInteraction;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 25, nullable = false)
    private RelationSource source;

    @Column(name = "assessed_by", length = 36, nullable = false)
    private String assessedBy;

    @Column(name = "assessed_at", nullable = false)
    private LocalDateTime assessedAt;
}
