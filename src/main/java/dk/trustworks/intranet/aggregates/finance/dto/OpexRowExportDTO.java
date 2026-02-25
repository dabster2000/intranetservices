package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing a single raw GL operating expense entry for the EBITDA source data export.
 * Sourced directly from {@code finance_details} joined to {@code accounting_accounts} and
 * {@code accounting_categories}, scoped to OPEX accounts (3000-5999 range) and excluding
 * 'Varesalg', 'Direkte omkostninger', and 'Igangvaerende arbejde' categories.
 *
 * Used as one row in the "OPEX" Excel tab of the EBITDA export.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpexRowExportDTO {

    /** UUID of the Trustworks legal entity that recorded this expense */
    private String companyUuid;

    /** Display name of the Trustworks legal entity */
    private String companyName;

    /** GL account number (e.g., 3100) */
    private int accountNumber;

    /** GL account description (e.g., "Telefon og internet") */
    private String accountDescription;

    /** Accounting category groupname (e.g., "Variable omkostninger") */
    private String categoryGroupname;

    /** Expense date in ISO format (YYYY-MM-DD) */
    private String expenseDate;

    /** Month key in YYYYMM format (e.g., "202407") */
    private String monthKey;

    /** User-friendly month label (e.g., "Jul 2024") */
    private String monthLabel;

    /** GL entry amount in DKK (positive = expense; negative = reversal/credit) */
    private double amountDkk;

    /** GL entry number from e-conomic ERP */
    private int entryNumber;

    /** Free-text description from the GL entry */
    private String text;
}
