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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** One captured e-conomic debtor line. Idempotent on entry_number. Period/code/consultant are voucher-resolved (D10). */
@Getter
@Setter
@Entity
@Table(name = "selfbilled_line")
public class SelfBilledLine extends PanacheEntityBase {

    @Id
    public String uuid;

    @Column(name = "source_uuid")          public String sourceUuid;
    @Column(name = "client_uuid")          public String clientUuid;
    @Column(name = "debtor_company_uuid")  public String debtorCompanyUuid;
    @Column(name = "account_number")       public int accountNumber;
    @Column(name = "voucher_number")       public int voucherNumber;
    @Column(name = "entry_number")         public long entryNumber;
    @Column(name = "booking_date")         public LocalDate bookingDate;
    @Column(name = "faktura_number")       public String fakturaNumber;
    @Column(name = "work_year")            public Integer workYear;
    @Column(name = "work_month")           public Integer workMonth;
    public String code;
    @Column(name = "consultant_uuid")      public String consultantUuid;
    @Column(name = "issuer_company_uuid")  public String issuerCompanyUuid;
    public BigDecimal amount;
    @Column(name = "source_text")          public String sourceText;

    @Enumerated(EnumType.STRING)
    public SelfBilledLineStatus status;

    @Column(name = "created_at", updatable = false) public LocalDateTime createdAt;
    @Column(name = "refreshed_at")                  public LocalDateTime refreshedAt;
    @Column(name = "marked_by")                     public String markedBy;
    @Column(name = "marked_at")                     public LocalDateTime markedAt;

    public SelfBilledLine() {}

    /** Existing row for an entry number (idempotency), or null. */
    public static SelfBilledLine findByEntry(long entryNumber) {
        return find("entryNumber", entryNumber).firstResult();
    }

    /** All sibling lines of a voucher (the netting unit). */
    public static List<SelfBilledLine> findVoucherSiblings(int accountNumber, int voucherNumber) {
        return list("accountNumber = ?1 and voucherNumber = ?2", accountNumber, voucherNumber);
    }
}
