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
 * <p><b>Third-party personal data.</b> Purpose is commercial relationship management. The
 * agreed retention is 24 months after the account's last activity, after which the name is
 * nulled — the purge job is NOT built, so nothing enforces that yet.
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

    /** Null when only the seat is known, not the person in it. */
    @Column(name = "name", length = 255)
    private String name;

    /** What to call the seat when nobody is named: "Programme director". */
    @Column(name = "role_label", length = 255)
    private String roleLabel;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @Column(name = "unit", length = 255, nullable = false)
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
