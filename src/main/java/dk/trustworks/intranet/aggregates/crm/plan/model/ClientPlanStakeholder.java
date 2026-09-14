package dk.trustworks.intranet.aggregates.crm.plan.model;

import dk.trustworks.intranet.aggregates.crm.plan.model.enums.BuyingRole;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.Influence;
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
 * One person at the client who matters to the plan (CRM spec §3.3, account-plan data
 * model).
 *
 * <p><b>Minimal on purpose — this is not a contact database.</b> No e-mail, no phone, no
 * address: only what account work needs. The data model calls this out as the safe first
 * version precisely so the first release does not quietly become a CRM contact store
 * nobody maintains.
 *
 * <p>{@link #name} may be null when only the SEAT is known — "the programme director,
 * whoever that turns out to be" is a real and useful row, and inventing a name to fill the
 * column would be worse than leaving it empty.
 *
 * <p>{@link #fromSignalUuid} records that the name arrived through a signal somebody filed.
 * Names are never typed into this table from nowhere; they come from a colleague who said
 * where they heard it.
 *
 * <p><b>Two ways a row gets here</b> (spec §3.6, V606). A seat typed into the plan — "the
 * programme director, whoever that turns out to be" — has no {@link #personUuid} and usually
 * a {@link #unit}. A <i>star</i> on the relationships tab has a {@link #personUuid} pointing
 * at the {@code account_person} row it was placed on, its {@link #name} and {@link #title}
 * copied off that row at the moment of starring, and no unit at all: the sources behind the
 * registry give a name and sometimes a job title, never an org unit.
 *
 * <p><b>Third-party personal data.</b> Purpose is commercial relationship management. The
 * agreed retention is 24 months after the account's last activity, after which the name is
 * nulled — {@code CrmRetentionPurgeService} (V608) does that, and nulls {@link #personUuid}
 * with it, since there is no foreign key to cascade from.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "client_plan_stakeholder")
public class ClientPlanStakeholder extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    /**
     * The {@code account_person} row a star was placed on (V606), or null for a seat typed
     * into the plan by hand — and for a row the V608 purge has erased.
     *
     * <p><b>Deliberately not a foreign key</b>, though the spec asked for one:
     * {@code client_plan_stakeholder} is copied to staging by {@code sp_sync_prod_to_staging}
     * and {@code account_person} is excluded from it, and a constraint that holds in one
     * database and not the other is a trap rather than a constraint. The
     * {@code ON DELETE SET NULL} the spec wanted happens in code, in
     * {@code CrmRetentionPurgeService}, which also has to null {@link #name} — something no
     * cascade could do for a copied string.
     */
    @Column(name = "person_uuid", length = 36)
    private String personUuid;

    /** Null when only the seat is known, not the person in it. */
    @Column(name = "name", length = 255)
    private String name;

    /** What to call the seat when nobody is named: "Programme director". */
    @Column(name = "role_label", length = 255)
    private String roleLabel;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    /**
     * The org unit a hand-typed stakeholder sits in ("IT Operations", "Indkøb"). NULL-able
     * since V606, and null on every starred person: the registry never carries a unit, and a
     * renderer that joins title and unit with a comma would print "CIO, " with nothing after
     * it on every starred row. Null rather than the old "—" placeholder, so the two cases —
     * "no unit" and "somebody typed a dash" — stay distinguishable.
     */
    @Column(name = "unit", length = 255)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(name = "buying", length = 20, nullable = false)
    private BuyingRole buying;

    @Enumerated(EnumType.STRING)
    @Column(name = "influence", length = 10, nullable = false)
    private Influence influence;

    /** When somebody last confirmed the row is still right — people change jobs. */
    @Column(name = "validated_at", nullable = false)
    private LocalDateTime validatedAt;

    @Column(name = "from_signal_uuid", length = 36)
    private String fromSignalUuid;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36, nullable = false)
    private String createdBy;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;

    @Column(name = "modified_by", length = 36, nullable = false)
    private String modifiedBy;
}
