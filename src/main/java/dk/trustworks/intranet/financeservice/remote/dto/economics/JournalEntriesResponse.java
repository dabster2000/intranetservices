package dk.trustworks.intranet.financeservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Response DTO for the e-conomic <em>classic</em> REST API
 * {@code GET /journals/{journalNumber}/entries} endpoint, restricted to the fields needed
 * to sync unbooked intercompany supplier-invoice drafts into {@code finance_details}.
 *
 * <p>Unlike the new Journals API ({@code /draft-entries}, see
 * {@code expenseservice.remote.JournalEntryResponse}), the classic-REST entry exposes the
 * cost account on {@code contraAccount} and the VAT rate on {@code contraVatAccount} —
 * both required to derive the net GL cost of a supplier-invoice draft. e-conomic supplier
 * invoices carry no top-level {@code account}; the cost leg is the {@code contraAccount}.
 *
 * @see <a href="https://restdocs.e-conomic.com/#get-journals-journalnumber-entries">e-conomic REST API — journal entries</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JournalEntriesResponse {

    public List<Entry> collection;
    public Pagination pagination;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        /** e.g. "supplierInvoice", "financeVoucher", "manualCustomerInvoice". */
        public String entryType;
        /** The seller's invoice number, e.g. "70-368". */
        public String supplierInvoiceNumber;
        public String text;
        /** Gross amount in the entry currency, signed in creditor convention. */
        public double amount;
        /** Gross amount in the agreement base currency (DKK). */
        public Double amountDefaultCurrency;
        /** Open-item remainder (full when unpaid). */
        public Double remainderDefaultCurrency;
        /** The cost account leg of a supplier invoice (e.g. 3050). */
        public AccountRef contraAccount;
        /** VAT account on the cost leg (e.g. I25 → ratePercentage 25.0). */
        public VatAccountRef contraVatAccount;
        public CurrencyRef currency;
        public Double exchangeRate;
        public String date;
        public Integer journalEntryNumber;
        public VoucherRef voucher;

        public int contraAccountNumber() {
            return contraAccount != null && contraAccount.accountNumber != null ? contraAccount.accountNumber : 0;
        }

        public double vatRatePercentage() {
            return contraVatAccount != null && contraVatAccount.ratePercentage != null ? contraVatAccount.ratePercentage : 0.0;
        }

        /** Gross in base currency, falling back to the entry-currency amount. */
        public double grossBaseAmount() {
            return amountDefaultCurrency != null ? amountDefaultCurrency : amount;
        }

        public int voucherNumber() {
            return voucher != null && voucher.voucherNumber != null ? voucher.voucherNumber : 0;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AccountRef {
        public Integer accountNumber;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VatAccountRef {
        public Double ratePercentage;
        public String vatCode;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CurrencyRef {
        public String code;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VoucherRef {
        public Integer voucherNumber;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Pagination {
        public String nextPage;
    }
}
