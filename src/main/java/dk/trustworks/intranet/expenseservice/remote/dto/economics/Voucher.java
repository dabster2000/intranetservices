package dk.trustworks.intranet.expenseservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.apache.commons.lang3.StringUtils;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "accountingYear",
        "journal",
        "entries"
})
public class Voucher {

    @JsonProperty("accountingYear")
    public AccountingYear accountingYear;
    @JsonProperty("journal")
    public Journal journal;
    @JsonProperty("entries")
    public Entries entries;

    public Voucher(){

    }
    public Voucher(AccountingYear accountingYear, Journal journal, Entries entries) {
        super();
        this.accountingYear = accountingYear;
        this.journal = journal;
        this.entries = entries;
    }

    @JsonProperty("accountingYear")
    public AccountingYear getAccountingYear() {
        return accountingYear;
    }

    @JsonProperty("accountingYear")
    public void setAccountingYear(AccountingYear accountingYear) {
        this.accountingYear = accountingYear;
    }

    @JsonProperty("journal")
    public Journal getJournal() {
        return journal;
    }

    @JsonProperty("journal")
    public void setJournal(Journal journal) {
        this.journal = journal;
    }

    @JsonProperty("entries")
    public Entries getEntries() {
        return entries;
    }

    @JsonProperty("entries")
    public void setEntries(Entries entries) {
        this.entries = entries;
    }

    @Override
    public String toString() {
        return "Voucher{" +
                "accountingYear='" + accountingYear + "'" +
                ", journal='" + journal + "'" +
                ", entries='" + StringUtils.join(" | ",entries) +
                '}';
    }
}

