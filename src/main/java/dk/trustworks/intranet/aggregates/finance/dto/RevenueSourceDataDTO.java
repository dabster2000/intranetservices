package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Container DTO for the accumulated revenue source data export.
 * Groups raw invoice and line-item records for the fiscal year into three lists
 * so callers (e.g., an Excel export) can work with each category independently.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RevenueSourceDataDTO {

    /**
     * Regular invoices and phantom invoices (type = INVOICE or PHANTOM)
     * with status = CREATED, within the fiscal year and matching CXO filters.
     */
    private List<InvoiceExportDTO> invoices;

    /**
     * All individual line items from both invoices and credit notes,
     * one row per invoiceitem record.
     */
    private List<InvoiceItemExportDTO> invoiceItems;

    /**
     * Credit notes (type = CREDIT_NOTE) with status = CREATED,
     * within the fiscal year and matching CXO filters.
     */
    private List<InvoiceExportDTO> creditNotes;
}
