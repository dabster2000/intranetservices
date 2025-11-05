package dk.trustworks.intranet.aggregates.invoice.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.aggregates.invoice.model.enums.*;
import dk.trustworks.intranet.model.Company;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

/**
 * Invoice - Unified invoice entity with separated status concerns.
 *
 * Key improvements:
 * - Separate type, lifecycle_status, finance_status, processing_state
 * - DECIMAL for money fields (no floating point errors)
 * - Explicit bill-to snapshot fields
 * - Cleaner audit trail with created_at/updated_at
 *
 * @see <a href="/docs/new-features/invoice-status-design/backend-developer_guide.md">Backend Developer Guide</a>
 */
@Getter
@Setter
@Entity
@Table(name = "invoices_v2")
public class Invoice extends PanacheEntityBase {

    @Id
    @Column(length = 36)
    private String uuid;

    // Company references
    @Column(name = "issuer_companyuuid", length = 36, nullable = false)
    private String issuerCompanyuuid;

    @Column(name = "debtor_companyuuid", length = 36)
    private String debtorCompanyuuid;

    // Invoice identification
    @Column(name = "invoicenumber")
    private Integer invoicenumber;

    @Column(name = "invoice_series", length = 20)
    private String invoiceSeries;

    // Separated status dimensions
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false)
    private LifecycleStatus lifecycleStatus = LifecycleStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "finance_status", nullable = false)
    private FinanceStatus financeStatus = FinanceStatus.NONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_state", nullable = false)
    private ProcessingState processingState = ProcessingState.IDLE;

    @Enumerated(EnumType.STRING)
    @Column(name = "queue_reason")
    private QueueReason queueReason;

    // Dates
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    @Column(name = "invoicedate")
    private LocalDate invoicedate;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    @Column(name = "duedate")
    private LocalDate duedate;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    @Column(name = "bookingdate")
    private LocalDate bookingdate;

    // Financial configuration
    @Column(length = 3, nullable = false)
    private String currency = "DKK";

    @Column(name = "vat_pct", precision = 5, scale = 2, nullable = false)
    private BigDecimal vatPct = new BigDecimal("25.00");

    @Column(name = "header_discount_pct", precision = 7, scale = 4, nullable = false)
    private BigDecimal headerDiscountPct = BigDecimal.ZERO;

    // References
    @Column(name = "contractuuid", length = 36)
    private String contractuuid;

    @Column(name = "projectuuid", length = 36)
    private String projectuuid;

    @Column(name = "source_invoice_uuid", length = 36)
    private String sourceInvoiceUuid;

    @Column(name = "creditnote_for_uuid", length = 36)
    private String creditnoteForUuid;

    // Bill-to snapshot (captured at creation time)
    @Column(name = "bill_to_name", length = 150)
    private String billToName;

    @Column(name = "bill_to_attn", length = 150)
    private String billToAttn;

    @Column(name = "bill_to_line1", length = 200)
    private String billToLine1;

    @Column(name = "bill_to_line2", length = 150)
    private String billToLine2;

    @Column(name = "bill_to_zip", length = 20)
    private String billToZip;

    @Column(name = "bill_to_city", length = 100)
    private String billToCity;

    @Column(name = "bill_to_country", length = 2)
    private String billToCountry;

    @Column(name = "bill_to_ean", length = 40)
    private String billToEan;

    @Column(name = "bill_to_cvr", length = 40)
    private String billToCvr;

    // ERP integration
    @Column(name = "economics_voucher_number", nullable = false)
    private Integer economicsVoucherNumber = 0;

    @Column(name = "pdf_url", length = 500)
    private String pdfUrl;

    @Column(name = "pdf_sha256", length = 64)
    private String pdfSha256;

    // Computed columns (read-only, managed by database)
    @Column(name = "invoice_year", insertable = false, updatable = false)
    private Integer invoiceYear;

    @Column(name = "invoice_month", insertable = false, updatable = false)
    private Integer invoiceMonth;

    // Audit timestamps
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Relationships
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "issuer_companyuuid", insertable = false, updatable = false)
    private Company company;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "invoiceuuid", referencedColumnName = "uuid")
    private List<InvoiceItem> invoiceitems = new LinkedList<>();

    // Transient calculated fields
    @Transient
    private Double sumBeforeDiscounts;

    @Transient
    private Double sumAfterDiscounts;

    @Transient
    private Double vatAmount;

    @Transient
    private Double grandTotal;

    @Transient
    private java.util.List<dk.trustworks.intranet.aggregates.invoice.pricing.CalculationBreakdownLine> calculationBreakdown;

    public Invoice() {
        this.uuid = UUID.randomUUID().toString();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Calculate sum of all line items before discounts.
     */
    public double getSumNoTax() {
        return invoiceitems.stream()
                .mapToDouble(ii -> ii.getHours() * ii.getRate())
                .sum();
    }

    /**
     * Calculate sum in DKK using exchange rate.
     */
    public double getSumWithNoTaxInDKK(double exchangeRate) {
        if ("DKK".equals(currency)) return getSumNoTax();
        return getSumNoTax() * exchangeRate;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "Invoice{" +
                "uuid='" + uuid + '\'' +
                ", type=" + type +
                ", lifecycleStatus=" + lifecycleStatus +
                ", financeStatus=" + financeStatus +
                ", invoicenumber=" + invoicenumber +
                '}';
    }
}
