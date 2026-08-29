package dk.trustworks.intranet.financeservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One month of bank-account cash flow for one company, imported from the
 * e-conomic Booked Entries API + Smart Bank draft legs by
 * {@code BankLiquidityService}. See V538 for full semantics — flows exclude
 * opening entries, so cumulative flows equal the accounting bank balance.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "fact_bank_flow_monthly")
public class BankFlowMonthly extends PanacheEntityBase {

    /** {@code companyuuid + "|" + monthKey} — deterministic upsert key. */
    @Id
    private String id;

    @Column(name = "companyuuid", nullable = false, length = 36)
    private String companyuuid;

    @Column(name = "month_key", nullable = false, length = 6)
    private String monthKey;

    @Column(name = "booked_flow_dkk", nullable = false)
    private double bookedFlowDkk;

    @Column(name = "draft_flow_dkk", nullable = false)
    private double draftFlowDkk;

    /** Subset of booked flow matching dividend text patterns (signed, usually negative). */
    @Column(name = "dividend_flow_dkk", nullable = false)
    private double dividendFlowDkk;

    @Column(name = "materialized_at", nullable = false)
    private LocalDateTime materializedAt;

    public static String idOf(String companyuuid, String monthKey) {
        return companyuuid + "|" + monthKey;
    }
}
