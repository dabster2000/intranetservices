package dk.trustworks.intranet.aggregates.invoice.network.dto;

import dk.trustworks.intranet.aggregates.invoice.model.enums.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * Invoice DTO V1 - Backward compatible API.
 *
 * Maintains legacy 'status' field by mapping from v2 status dimensions:
 * - status = type=='CREDIT_NOTE' ? 'CREDIT_NOTE' : (processingState=='QUEUED' ? 'QUEUED' : lifecycleStatus)
 * - economics_status = financeStatus
 *
 * Deprecated: Use InvoiceDtoV2 for new integrations.
 *
 * @deprecated Use {@link InvoiceDtoV2} for clean separated status fields
 * @see <a href="/docs/new-features/invoice-status-design/api-migration_guide.md">API Migration Guide</a>
 */
@Data
@Deprecated(since = "V109", forRemoval = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InvoiceDtoV1 {
    private String uuid;
    private String companyuuid;  // Maps to issuerCompanyuuid
    private String debtorCompanyuuid;
    private Integer invoicenumber;
    private String contractuuid;
    private String projectuuid;
    private Integer year;
    private Integer month;
    private Double discount;  // Maps from headerDiscountPct
    private String clientname;
    private String clientaddresse;
    private String otheraddressinfo;
    private String zipcity;
    private String cvr;
    private String ean;
    private String attention;
    private String invoiceRefUuid;  // Maps to sourceInvoiceUuid
    private String creditnoteForUuid;
    private String currency;
    private Double vat;  // Maps from vatPct
    private LocalDate invoicedate;
    private LocalDate duedate;
    private LocalDate bookingdate;
    private Integer economicsVoucherNumber;

    // Legacy consolidated fields
    private InvoiceType type;
    private String status;  // Derived from lifecycleStatus + processingState + type
    private String economicsStatus;  // Maps from financeStatus

    // Also expose new fields for gradual migration
    private LifecycleStatus lifecycleStatus;
    private FinanceStatus financeStatus;
    private ProcessingState processingState;
    private QueueReason queueReason;

    // Line items
    private List<InvoiceItemDto> invoiceitems;

    // Calculated totals
    private Double sumBeforeDiscounts;
    private Double sumAfterDiscounts;
    private Double vatAmount;
    private Double grandTotal;
}
