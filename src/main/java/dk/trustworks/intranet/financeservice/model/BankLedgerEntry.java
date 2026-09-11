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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One bank- or debtor-ledger line for one company, imported from e-conomic by
 * {@code BankLiquidityService}. See V583 for the full semantics: bank rows are
 * signed cash movements (opening entries excluded), debtor rows are signed
 * receivable movements keyed by customer invoice number.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "fact_bank_ledger_entry")
public class BankLedgerEntry extends PanacheEntityBase {

    public static final String LEDGER_BANK = "BANK";
    public static final String LEDGER_DEBTOR = "DEBTOR";

    /** {@code companyuuid|ledger|B|entryNumber} for booked rows, {@code ...|D|<hash>} for drafts. */
    @Id
    private String id;

    @Column(name = "companyuuid", nullable = false, length = 36)
    private String companyuuid;

    @Column(name = "ledger", nullable = false, length = 8)
    private String ledger;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "amount_dkk", nullable = false)
    private double amountDkk;

    /** e-conomic entry type (1 invoice, 2 customer payment, 4 supplier payment, 5 finance voucher); null for drafts. */
    @Column(name = "entry_type")
    private Integer entryType;

    @Column(name = "is_draft", nullable = false)
    private boolean draft;

    /** {@code FlowKind} name. */
    @Column(name = "kind", nullable = false, length = 24)
    private String kind;

    @Column(name = "customer_invoice_number")
    private Integer customerInvoiceNumber;

    /** e-conomic's open amount on the line at import time (debtor ledger); null for bank lines and drafts. */
    @Column(name = "remainder_dkk")
    private Double remainderDkk;

    @Column(name = "entry_text", length = 255)
    private String entryText;

    @Column(name = "materialized_at", nullable = false)
    private LocalDateTime materializedAt;
}
