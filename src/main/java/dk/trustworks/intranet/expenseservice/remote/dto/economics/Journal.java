package dk.trustworks.intranet.expenseservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "journalNumber",
        "self"
})
public class Journal {

    @JsonProperty("journalNumber")
    public int journalNumber;

    @JsonProperty("self")
    public String self;

    public Journal(){

    }
    public Journal(int journalNumber) {
        super();
        this.journalNumber = journalNumber;
    }

    @JsonProperty("journalNumber")
    public int getJournalNumber() {
        return journalNumber;
    }

    @JsonProperty("journalNumber")
    public void setJournalNumber(int journalNumber) {
        this.journalNumber = journalNumber;
    }

    @JsonProperty("self")
    public String getSelf() {
        return self;
    }

    @JsonProperty("self")
    public void setSelf(String self) {
        this.self = self;
    }

    @Override
    public String toString() {
        return "Journal{" +
                "journalNumber='" + journalNumber +
                '}';
    }
}


