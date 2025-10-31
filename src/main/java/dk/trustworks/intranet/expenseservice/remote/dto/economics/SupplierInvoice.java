package dk.trustworks.intranet.expenseservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "account",
        "text",
        "amount",
        "contraAccount",
        "supplierInvoiceNumber",
        "date"
})
public class SupplierInvoice {

    @JsonProperty("account")
    public ExpenseAccount account;
    @JsonProperty("text")
    public String text;
    @JsonProperty("amount")
    public double amount;
    @JsonProperty("contraAccount")
    public ContraAccount contraAccount;
    @JsonProperty("date")
    public String date;
    @JsonProperty("supplierInvoiceNumber")
    public int supplierInvoiceNumber;

    public SupplierInvoice(){

    }
    public SupplierInvoice(ExpenseAccount account, int supplierInvoiceNumber, String text, double amount,
                                 ContraAccount contraAccount, String date) {
        this.account = account;
        this.supplierInvoiceNumber = supplierInvoiceNumber;
        this.text = text;
        this.amount = amount;
        this.contraAccount = contraAccount;
        this.date = date;
    }

    @JsonProperty("account")
    public ExpenseAccount getAccount() {
        return account;
    }

    @JsonProperty("account")
    public void setAccount(ExpenseAccount account) {
        this.account = account;
    }

    @JsonProperty("text")
    public String getText() {
        return text;
    }

    @JsonProperty("text")
    public void setText(String text) {
        this.text = text;
    }

    @JsonProperty("amount")
    public double getAmount() {
        return amount;
    }

    @JsonProperty("amount")
    public void setAmount(double amount) {
        this.amount = amount;
    }

    @JsonProperty("contraAccount")
    public ContraAccount getContraAccount() {
        return contraAccount;
    }

    @JsonProperty("contraAccount")
    public void setContraAccount(ContraAccount contraAccount) {
        this.contraAccount = contraAccount;
    }

    @JsonProperty("date")
    public String getDate() {
        return date;
    }

    @JsonProperty("date")
    public void setDate(String date) {
        this.date = date;
    }

    @JsonProperty("supplierInvoiceNumber")
    public int getSupplierInvoiceNumber() {
        return supplierInvoiceNumber;
    }

    @JsonProperty("supplierInvoiceNumber")
    public void setSupplierInvoiceNumber(int supplierInvoiceNumber) {
        this.supplierInvoiceNumber = supplierInvoiceNumber;
    }

    @Override
    public String toString() {
        return "SupplierInvoice{" +
                "account='" + account + '\'' +
                ", text='" + text + '\'' +
                ", amount='" + amount + '\'' +
                ", contraAccount='" + contraAccount + '\'' +
                ", supplierInvoiceNumber=" + supplierInvoiceNumber +
                ", date='" + date +
                '}';
    }
}
