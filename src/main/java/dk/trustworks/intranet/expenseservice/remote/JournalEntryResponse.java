package dk.trustworks.intranet.expenseservice.remote;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Response DTO for e-conomic Journals API GET /journals/{journalNumber}/entries endpoint.
 * Contains collection of journal entries with metadata needed for deletion.
 *
 * @see <a href="https://apis.e-conomic.com/journalsapi/redoc.html">e-conomic Journals API Documentation</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JournalEntryResponse {
    public List<Entry> collection;
    public Pagination pagination;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        public int entryNumber;
        public int voucherNumber;
        public String objectVersion;
        public Account account;
        public String text;
        public double amount;
        public String date;

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Account {
            public int accountNumber;
            public String name;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Pagination {
        public int skipPages;
        public int pageSize;
        public int maxPageSizeAllowed;
        public String firstPage;
        public String nextPage;
        public String lastPage;
    }
}
