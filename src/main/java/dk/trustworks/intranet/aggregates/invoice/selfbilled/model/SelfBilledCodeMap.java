package dk.trustworks.intranet.aggregates.invoice.selfbilled.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Magnit consultant code → Trustworks consultant. Unique per (agreement, account, code). */
@Getter
@Setter
@Entity
@Table(name = "selfbilled_code_map")
public class SelfBilledCodeMap extends PanacheEntityBase {

    @Id
    public String uuid;

    @Column(name = "agreement_company_uuid")
    public String agreementCompanyUuid;

    @Column(name = "account_number")
    public int accountNumber;

    public String code;

    @Column(name = "consultant_uuid")
    public String consultantUuid;

    @Column(name = "created_at", updatable = false)
    public LocalDateTime createdAt;

    @Column(name = "created_by")
    public String createdBy;

    public SelfBilledCodeMap() {}

    /** Resolve one code, or null. */
    public static SelfBilledCodeMap findMapping(String agreementCompanyUuid, int accountNumber, String code) {
        return find("agreementCompanyUuid = ?1 and accountNumber = ?2 and code = ?3",
                agreementCompanyUuid, accountNumber, code).firstResult();
    }
}
