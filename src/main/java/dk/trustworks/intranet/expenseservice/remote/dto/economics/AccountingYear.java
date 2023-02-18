package dk.trustworks.intranet.expenseservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "year",
        "self"
})
public class AccountingYear {

    @JsonProperty("year")
    public String year;

    @JsonProperty("self")
    public String self;

    public AccountingYear(){

    }
    public AccountingYear(String year) {
        super();
        this.year = year;
    }

    @JsonProperty("year")
    public String getYear() {
        return year;
    }

    @JsonProperty("year")
    public void setYear(String year) {
        this.year = year;
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
        return "AccountingYear{" +
                "year='" + year + "' " +
                "self='" + self +
                '}';
    }
}