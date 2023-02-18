package dk.trustworks.intranet.expenseservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "accountnumber",
        "self"
})
public class ExpenseAccount {

    @JsonProperty("accountNumber")
    public int accountNumber;

    @JsonProperty("self")
    public String self;

    public ExpenseAccount(){
    }

    public ExpenseAccount(int accountNumber) {
        this.accountNumber = accountNumber;
    }

    @JsonProperty("accountNumber")
    public int getAccountNumber() {
        return accountNumber;
    }

    @JsonProperty("accountNumber")
    public void setAccountNumber(int accountNumber) {
        this.accountNumber = accountNumber;
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
        return "Account{" +
                "accountNumber='" + accountNumber +
                '}';
    }
}
