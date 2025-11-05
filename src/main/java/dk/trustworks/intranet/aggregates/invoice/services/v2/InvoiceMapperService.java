package dk.trustworks.intranet.aggregates.invoice.services.v2;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.*;
import dk.trustworks.intranet.aggregates.invoice.network.dto.InvoiceDtoV1;
import dk.trustworks.intranet.aggregates.invoice.network.dto.InvoiceDtoV2;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;

/**
 * Maps between Invoice entity and DTOs (V1 backward-compatible and V2 clean).
 *
 * @see <a href="/docs/new-features/invoice-status-design/backend-developer_guide.md">Backend Developer Guide - Section 4.4</a>
 */
@ApplicationScoped
public class InvoiceMapperService {

    /**
     * Map Invoice entity to V2 DTO (clean API).
     */
    public InvoiceDtoV2 toV2Dto(Invoice invoice) {
        if (invoice == null) return null;

        InvoiceDtoV2 dto = new InvoiceDtoV2();
        dto.setUuid(invoice.getUuid());
        dto.setIssuerCompanyuuid(invoice.getIssuerCompanyuuid());
        dto.setDebtorCompanyuuid(invoice.getDebtorCompanyuuid());
        dto.setInvoicenumber(invoice.getInvoicenumber());
        dto.setInvoiceSeries(invoice.getInvoiceSeries());

        // Status dimensions
        dto.setType(invoice.getType());
        dto.setLifecycleStatus(invoice.getLifecycleStatus());
        dto.setFinanceStatus(invoice.getFinanceStatus());
        dto.setProcessingState(invoice.getProcessingState());
        dto.setQueueReason(invoice.getQueueReason());

        // Dates
        dto.setInvoicedate(invoice.getInvoicedate());
        dto.setDuedate(invoice.getDuedate());
        dto.setBookingdate(invoice.getBookingdate());

        // Financial
        dto.setCurrency(invoice.getCurrency());
        dto.setVatPct(invoice.getVatPct());
        dto.setHeaderDiscountPct(invoice.getHeaderDiscountPct());

        // References
        dto.setContractuuid(invoice.getContractuuid());
        dto.setProjectuuid(invoice.getProjectuuid());
        dto.setSourceInvoiceUuid(invoice.getSourceInvoiceUuid());
        dto.setCreditnoteForUuid(invoice.getCreditnoteForUuid());

        // Bill-to snapshot
        InvoiceDtoV2.BillToSnapshot billTo = new InvoiceDtoV2.BillToSnapshot();
        billTo.setName(invoice.getBillToName());
        billTo.setAttn(invoice.getBillToAttn());
        billTo.setLine1(invoice.getBillToLine1());
        billTo.setLine2(invoice.getBillToLine2());
        billTo.setZip(invoice.getBillToZip());
        billTo.setCity(invoice.getBillToCity());
        billTo.setCountry(invoice.getBillToCountry());
        billTo.setEan(invoice.getBillToEan());
        billTo.setCvr(invoice.getBillToCvr());
        dto.setBillTo(billTo);

        // ERP
        dto.setEconomicsVoucherNumber(invoice.getEconomicsVoucherNumber());
        dto.setPdfUrl(invoice.getPdfUrl());
        dto.setPdfSha256(invoice.getPdfSha256());

        // Computed
        dto.setInvoiceYear(invoice.getInvoiceYear());
        dto.setInvoiceMonth(invoice.getInvoiceMonth());

        // Audit
        dto.setCreatedAt(invoice.getCreatedAt());
        dto.setUpdatedAt(invoice.getUpdatedAt());

        return dto;
    }

    /**
     * Map Invoice entity to V1 DTO (backward compatible API).
     *
     * Derives legacy 'status' field from type, lifecycleStatus, and processingState.
     */
    public InvoiceDtoV1 toV1Dto(Invoice invoice) {
        if (invoice == null) return null;

        InvoiceDtoV1 dto = new InvoiceDtoV1();
        dto.setUuid(invoice.getUuid());
        dto.setCompanyuuid(invoice.getIssuerCompanyuuid());
        dto.setDebtorCompanyuuid(invoice.getDebtorCompanyuuid());
        dto.setInvoicenumber(invoice.getInvoicenumber());
        dto.setContractuuid(invoice.getContractuuid());
        dto.setProjectuuid(invoice.getProjectuuid());
        dto.setYear(invoice.getInvoiceYear());
        dto.setMonth(invoice.getInvoiceMonth());

        // Type conversions
        dto.setDiscount(invoice.getHeaderDiscountPct() != null ? invoice.getHeaderDiscountPct().doubleValue() : 0.0);
        dto.setVat(invoice.getVatPct() != null ? invoice.getVatPct().doubleValue() : 0.0);

        // Address reconstruction
        dto.setClientname(invoice.getBillToName());
        dto.setClientaddresse(invoice.getBillToLine1());
        dto.setOtheraddressinfo(invoice.getBillToLine2());
        dto.setZipcity(combineZipCity(invoice.getBillToZip(), invoice.getBillToCity()));
        dto.setCvr(invoice.getBillToCvr());
        dto.setEan(invoice.getBillToEan());
        dto.setAttention(invoice.getBillToAttn());

        dto.setInvoiceRefUuid(invoice.getSourceInvoiceUuid());
        dto.setCreditnoteForUuid(invoice.getCreditnoteForUuid());
        dto.setCurrency(invoice.getCurrency());
        dto.setInvoicedate(invoice.getInvoicedate());
        dto.setDuedate(invoice.getDuedate());
        dto.setBookingdate(invoice.getBookingdate());
        dto.setEconomicsVoucherNumber(invoice.getEconomicsVoucherNumber());

        // Type and new status fields
        dto.setType(invoice.getType());
        dto.setLifecycleStatus(invoice.getLifecycleStatus());
        dto.setFinanceStatus(invoice.getFinanceStatus());
        dto.setProcessingState(invoice.getProcessingState());
        dto.setQueueReason(invoice.getQueueReason());

        // Derived legacy status field
        dto.setStatus(deriveLegacyStatus(invoice.getType(), invoice.getLifecycleStatus(), invoice.getProcessingState()));
        dto.setEconomicsStatus(mapFinanceStatusToLegacy(invoice.getFinanceStatus()));

        return dto;
    }

    /**
     * Derive legacy single 'status' field from v2 status dimensions.
     *
     * Logic:
     * - If type is CREDIT_NOTE → status = "CREDIT_NOTE"
     * - Else if processingState is QUEUED → status = "QUEUED"
     * - Else → status = lifecycleStatus.name()
     */
    private String deriveLegacyStatus(InvoiceType type, LifecycleStatus lifecycleStatus, ProcessingState processingState) {
        if (type == InvoiceType.CREDIT_NOTE) {
            return "CREDIT_NOTE";
        }
        if (processingState == ProcessingState.QUEUED) {
            return "QUEUED";
        }
        return lifecycleStatus.name();
    }

    /**
     * Map FinanceStatus to legacy economics_status string.
     */
    private String mapFinanceStatusToLegacy(FinanceStatus financeStatus) {
        if (financeStatus == null) return "NA";
        return switch (financeStatus) {
            case NONE, ERROR -> "NA";
            case UPLOADED -> "UPLOADED";
            case BOOKED -> "BOOKED";
            case PAID -> "PAID";
        };
    }

    /**
     * Combine zip and city for legacy zipcity field.
     */
    private String combineZipCity(String zip, String city) {
        if (zip == null && city == null) return null;
        if (zip == null) return city;
        if (city == null) return zip;
        return zip + " " + city;
    }
}
