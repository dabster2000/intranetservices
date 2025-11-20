package dk.trustworks.intranet.expenseservice.remote;

/**
 * Request DTO for deleting draft entries via e-conomic Journals API.
 * Used with DELETE /draft-entries endpoint.
 *
 * @see <a href="https://apis.e-conomic.com/journalsapi/redoc.html">e-conomic Journals API Documentation</a>
 */
public class DraftEntryDeleteRequest {
    public int journalNumber;
    public int voucherNumber;
    public int entryNumber;
    public String objectVersion;

    public DraftEntryDeleteRequest() {
    }

    public DraftEntryDeleteRequest(int journalNumber, int voucherNumber, int entryNumber, String objectVersion) {
        this.journalNumber = journalNumber;
        this.voucherNumber = voucherNumber;
        this.entryNumber = entryNumber;
        this.objectVersion = objectVersion;
    }
}
