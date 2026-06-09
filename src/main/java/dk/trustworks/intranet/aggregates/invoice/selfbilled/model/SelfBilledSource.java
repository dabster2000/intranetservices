package dk.trustworks.intranet.aggregates.invoice.selfbilled.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/** Config: one (agreement company, e-conomic account) → billing client. Active-record Panache. */
@Getter
@Setter
@Entity
@Table(name = "selfbilled_source")
public class SelfBilledSource extends PanacheEntityBase {

    @Id
    public String uuid;

    @Column(name = "agreement_company_uuid")
    public String agreementCompanyUuid;

    @Column(name = "account_number")
    public int accountNumber;

    @Column(name = "client_uuid")
    public String clientUuid;

    public boolean enabled;

    public String label;

    @Column(name = "created_at", updatable = false)
    public LocalDateTime createdAt;

    public SelfBilledSource() {}

    /** All enabled sources, deterministic order (account asc). */
    public static List<SelfBilledSource> listEnabled() {
        return list("enabled = ?1 order by accountNumber", true);
    }
}
