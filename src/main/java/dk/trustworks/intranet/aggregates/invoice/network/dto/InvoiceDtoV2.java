package dk.trustworks.intranet.aggregates.invoice.network.dto;

import dk.trustworks.intranet.aggregates.invoice.model.enums.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Invoice DTO V2 - Clean API with separated status dimensions.
 *
 * Breaking changes from V1:
 * - Removed single 'status' field
 * - Added separate type, lifecycleStatus, financeStatus, processingState
 * - Changed vat (double) → vatPct (BigDecimal)
 * - Changed discount (double) → headerDiscountPct (BigDecimal)
 * - Added explicit bill-to fields
 *
 * @see <a href="/docs/new-features/invoice-status-design/api-migration_guide.md">API Migration Guide</a>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InvoiceDtoV2 {
    private String uuid;
    private String issuerCompanyuuid;
    private String debtorCompanyuuid;
    private Integer invoicenumber;
    private String invoiceSeries;

    // Separated status dimensions
    private InvoiceType type;
    private LifecycleStatus lifecycleStatus;
    private FinanceStatus financeStatus;
    private ProcessingState processingState;
    private QueueReason queueReason;

    // Dates
    private LocalDate invoicedate;
    private LocalDate duedate;
    private LocalDate bookingdate;

    // Financial configuration
    private String currency;
    private BigDecimal vatPct;
    private BigDecimal headerDiscountPct;

    // References
    private String contractuuid;
    private String projectuuid;
    private String sourceInvoiceUuid;
    private String creditnoteForUuid;

    // Bill-to snapshot
    private BillToSnapshot billTo;

    // ERP integration
    private Integer economicsVoucherNumber;
    private String pdfUrl;
    private String pdfSha256;

    // Computed
    private Integer invoiceYear;
    private Integer invoiceMonth;

    // Audit
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Line items (optional, loaded on demand)
    private List<InvoiceItemDto> items;

    // Calculated totals
    private BigDecimal sumBeforeDiscounts;
    private BigDecimal sumAfterDiscounts;
    private BigDecimal vatAmount;
    private BigDecimal grandTotal;

    @Data
    public static class BillToSnapshot {
        private String name;
        private String attn;
        private String line1;
        private String line2;
        private String zip;
        private String city;
        private String country;
        private String ean;
        private String cvr;
    }
}
