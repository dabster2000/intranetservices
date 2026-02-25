package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Container DTO for the Expected Accumulated EBITDA source data export.
 * Groups the four raw data sources that drive the EBITDA calculation into separate lists
 * so callers (e.g., an Excel export) can build dedicated worksheet tabs per category.
 *
 * <p>EBITDA formula (per fiscal month):
 * <pre>
 *   Monthly EBITDA = Revenue − Direct Cost − OPEX
 * </pre>
 * where Revenue and Direct Cost come from {@code invoices}/{@code creditNotes}/{@code directCosts},
 * and {@code internalInvoices} is provided as a reference line (not subtracted again — it is
 * already captured inside the direct costs via individual company cost attribution).
 *
 * <p>Filter scoping:
 * <ul>
 *   <li>companyIds filter applies to ALL four data sources.</li>
 *   <li>sector/serviceLine/contractType/clientId filters apply ONLY to
 *       {@code invoices}, {@code creditNotes}, and {@code directCosts}.</li>
 *   <li>{@code internalInvoices} and {@code opexEntries} are never filtered by dimension
 *       (sector/serviceLine/contractType/clientId) — they are company-scoped only.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EbitdaSourceDataDTO {

    /**
     * Regular invoices and phantom invoices (type = INVOICE or PHANTOM) with status = CREATED,
     * within the fiscal year and matching CXO dimension filters.
     * Same query as {@code RevenueSourceDataDTO.invoices}.
     */
    private List<InvoiceExportDTO> invoices;

    /**
     * Credit notes (type = CREDIT_NOTE) with status = CREATED,
     * within the fiscal year and matching CXO dimension filters.
     * Same query as {@code RevenueSourceDataDTO.creditNotes}.
     */
    private List<InvoiceExportDTO> creditNotes;

    /**
     * Direct delivery costs from {@code fact_project_financials_mat} at project×month granularity,
     * within the fiscal year and matching all CXO dimension filters.
     */
    private List<DirectCostRowDTO> directCosts;

    /**
     * Intercompany (INTERNAL) invoice costs within the fiscal year, scoped by companyIds only.
     * Contains both QUEUED_INVOICE rows (from invoices table) and CREATED_GL rows
     * (from finance_details accounts 3050/3055/3070/3075/1350).
     * Provided as a reference/transparency tab — not double-counted in the EBITDA formula.
     */
    private List<InternalInvoiceRowDTO> internalInvoices;

    /**
     * Raw GL operating expense entries from {@code finance_details} within the fiscal year,
     * scoped by companyIds only.
     * Excludes 'Varesalg', 'Direkte omkostninger', and 'Igangvaerende arbejde' categories.
     */
    private List<OpexRowExportDTO> opexEntries;
}
