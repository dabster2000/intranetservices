
package dk.trustworks.intranet.financeservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.Customer;
import lombok.Data;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "account",
        "amount",
        "amountInBaseCurrency",
        "currency",
        "date",
        "entryNumber",
        "text",
        "entryType",
        "voucherNumber",
        "self",
        "customer",
        "invoiceNumber",
        "remainder",
        "remainderInBaseCurrency"
})
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class Collection {

    @JsonProperty("account")
    public Account account;
    @JsonProperty("amount")
    public double amount;
    @JsonProperty("amountInBaseCurrency")
    public double amountInBaseCurrency;
    @JsonProperty("currency")
    public String currency;
    @JsonProperty("date")
    public String date;
    @JsonProperty("entryNumber")
    public int entryNumber;
    @JsonProperty("text")
    public String text;
    @JsonProperty("entryType")
    public String entryType;
    @JsonProperty("voucherNumber")
    public int voucherNumber;
    @JsonProperty("self")
    public String self;
    @JsonProperty("customer")
    public Customer customer;
    @JsonProperty("invoiceNumber")
    public int invoiceNumber;
    @JsonProperty("remainder")
    public int remainder;
    @JsonProperty("remainderInBaseCurrency")
    public int remainderInBaseCurrency;

}
