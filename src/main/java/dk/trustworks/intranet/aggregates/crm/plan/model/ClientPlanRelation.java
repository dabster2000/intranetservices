package dk.trustworks.intranet.aggregates.crm.plan.model;

import dk.trustworks.intranet.aggregates.crm.plan.model.enums.RelationRole;
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

import java.time.LocalDateTime;

/**
 * Where the account owner wants one Trustworks person's relationship with one stakeholder to
 * be — 0-4, and nothing about where it is today.
 *
 * <h2>What left this table in the 2026-09-14 cut, and why (spec §3.6, V606)</h2>
 * {@code current_level}, {@code last_interaction} and {@code source} are gone. They were the
 * plan's own guess at how well a colleague knows somebody, and that fact now has an owner:
 * the colleague themselves, through {@code account_relation_claim} (V605), and the calendar,
 * through the {@code MET} edges the relationships tab derives. Two stores of one fact drift,
 * and the one that drifts is always the one nobody updates — the plan's copy was typed once
 * in a review and never again.
 *
 * <p>What stays is the part only the account owner can say: the <b>target</b>, the role, and
 * who set them. That is a plan decision, not an observation, so nothing else can derive it.
 *
 * <p><b>A rating records who made it and when</b> ({@link #assessedBy}, {@link #assessedAt})
 * — rule 5 of the account-plan data model. An unattributed "4" is an opinion pretending to
 * be a fact, and a rating from eighteen months ago is not the same claim as one from last
 * week. The tab shows a rating older than 180 days as stale for that reason.
 *
 * <p>The column is {@code target_level}, not {@code target}, for the same reason its
 * departed twin was {@code current_level}: {@code CURRENT} is a MariaDB reserved word and an
 * unquoted one fails CREATE TABLE at parse time, which is how V534's {@code lines} column
 * took down the staging canary.
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

    /** 0-4, how strong it needs to be for the plan to work. */
    @Column(name = "target_level", nullable = false)
    private int targetLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 15, nullable = false)
    private RelationRole role;

    @Column(name = "assessed_by", length = 36, nullable = false)
    private String assessedBy;

    @Column(name = "assessed_at", nullable = false)
    private LocalDateTime assessedAt;
}
