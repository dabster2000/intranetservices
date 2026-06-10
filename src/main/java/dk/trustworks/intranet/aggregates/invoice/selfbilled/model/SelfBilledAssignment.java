package dk.trustworks.intranet.aggregates.invoice.selfbilled.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * The audited human decision: one self-billing document (voucher, anchored by its
 * parseable line) -> consultant + work-period. share_amount is SIGNED AS POSTED
 * (revenue negative, like selfbilled_line.amount); normally = the voucher net,
 * smaller only for a manual split (spec §7). Authority for settlement targets —
 * the parsed columns on selfbilled_line are suggestions only.
 */
@Getter
@Setter
@Entity
@Table(name = "selfbilled_assignment")
public class SelfBilledAssignment extends PanacheEntityBase {

    @Id
    public String uuid;

    @Column(name = "selfbilled_line_uuid") public String selfbilledLineUuid;
    @Column(name = "consultant_uuid")      public String consultantUuid;
    @Column(name = "work_year")            public int workYear;
    @Column(name = "work_month")           public int workMonth;
    @Column(name = "share_amount")         public BigDecimal shareAmount;
    @Column(name = "assigned_by")          public String assignedBy;
    @Column(name = "assigned_at")          public LocalDateTime assignedAt;

    @Enumerated(EnumType.STRING)
    public AssignmentSourceType source;

    public SelfBilledAssignment() {}

    /** All assignments anchored to any of the given line uuids (a voucher's siblings). */
    public static List<SelfBilledAssignment> findByLines(Collection<String> lineUuids) {
        if (lineUuids == null || lineUuids.isEmpty()) return List.of();
        return list("selfbilledLineUuid in ?1", lineUuids);
    }
}
