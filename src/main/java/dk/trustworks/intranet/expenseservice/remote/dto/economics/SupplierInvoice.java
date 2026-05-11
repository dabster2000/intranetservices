package dk.trustworks.intranet.expenseservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "account",
        "supplier",
        "text",
        "amount",
        "contraAccount",
        "contraVatAccount",
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
    public String supplierInvoiceNumber;

    @JsonProperty("supplier")
    public Supplier supplier;

    @JsonProperty("contraVatAccount")
    public VatAccount contraVatAccount;

    public SupplierInvoice(){

    }
    public SupplierInvoice(ExpenseAccount account, String supplierInvoiceNumber, String text, double amount,
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
    public String getSupplierInvoiceNumber() {
        return supplierInvoiceNumber;
    }

    @JsonProperty("supplierInvoiceNumber")
    public void setSupplierInvoiceNumber(String supplierInvoiceNumber) {
        this.supplierInvoiceNumber = supplierInvoiceNumber;
    }

    @JsonProperty("supplier")
    public Supplier getSupplier() {
        return supplier;
    }

    @JsonProperty("supplier")
    public void setSupplier(Supplier supplier) {
        this.supplier = supplier;
    }

    @JsonProperty("contraVatAccount")
    public VatAccount getContraVatAccount() {
        return contraVatAccount;
    }

    @JsonProperty("contraVatAccount")
    public void setContraVatAccount(VatAccount contraVatAccount) {
        this.contraVatAccount = contraVatAccount;
    }

    @Override
    public String toString() {
        return "SupplierInvoice{" +
                "account='" + account + '\'' +
                ", supplier=" + supplier +
                ", text='" + text + '\'' +
                ", amount='" + amount + '\'' +
                ", contraAccount='" + contraAccount + '\'' +
                ", contraVatAccount=" + contraVatAccount +
                ", supplierInvoiceNumber=" + supplierInvoiceNumber +
                ", date='" + date +
                '}';
    }
}
