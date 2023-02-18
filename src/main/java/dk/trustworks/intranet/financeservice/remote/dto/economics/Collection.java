
package dk.trustworks.intranet.financeservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.*;

import java.util.HashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "account",
    "amount",
    "amountInBaseCurrency",
    "currency",
    "date",
    "dueDate",
    "entryNumber",
    "text",
    "entryType",
    "vatAccount",
    "voucherNumber",
    "invoiceNumber",
    "self"
})
public class Collection {

    @JsonProperty("account")
    private Account account;
    @JsonProperty("amount")
    private double amount;
    @JsonProperty("amountInBaseCurrency")
    private double amountInBaseCurrency;
    @JsonProperty("currency")
    private String currency;
    @JsonProperty("date")
    private String date;
    @JsonProperty("dueDate")
    private String dueDate;
    @JsonProperty("entryNumber")
    private int entryNumber;
    @JsonProperty("text")
    private String text;
    @JsonProperty("entryType")
    private String entryType;
    @JsonProperty("vatAccount")
    private VatAccount vatAccount;
    @JsonProperty("voucherNumber")
    private int voucherNumber;
    @JsonProperty("invoiceNumber")
    private int invoiceNumber;
    @JsonProperty("self")
    private String self;
    @JsonIgnore
    private final Map<String, Object> additionalProperties = new HashMap<String, Object>();

    /**
     * No args constructor for use in serialization
     * 
     */
    public Collection() {
    }

    /**
     * 
     * @param date
     * @param amountInBaseCurrency
     * @param entryType
     * @param amount
     * @param dueDate
     * @param voucherNumber
     * @param vatAccount
     * @param invoiceNumber
     * @param self
     * @param currency
     * @param entryNumber
     * @param text
     * @param account
     */
    public Collection(Account account, double amount, double amountInBaseCurrency, String currency, String date, String dueDate, int entryNumber, String text, String entryType, VatAccount vatAccount, int voucherNumber, int invoiceNumber, String self) {
        super();
        this.account = account;
        this.amount = amount;
        this.amountInBaseCurrency = amountInBaseCurrency;
        this.currency = currency;
        this.date = date;
        this.dueDate = dueDate;
        this.entryNumber = entryNumber;
        this.text = text;
        this.entryType = entryType;
        this.vatAccount = vatAccount;
        this.voucherNumber = voucherNumber;
        this.invoiceNumber = invoiceNumber;
        this.self = self;
    }

    @JsonProperty("account")
    public Account getAccount() {
        return account;
    }

    @JsonProperty("account")
    public void setAccount(Account account) {
        this.account = account;
    }

    @JsonProperty("amount")
    public double getAmount() {
        return amount;
    }

    @JsonProperty("amount")
    public void setAmount(double amount) {
        this.amount = amount;
    }

    @JsonProperty("amountInBaseCurrency")
    public double getAmountInBaseCurrency() {
        return amountInBaseCurrency;
    }

    @JsonProperty("amountInBaseCurrency")
    public void setAmountInBaseCurrency(double amountInBaseCurrency) {
        this.amountInBaseCurrency = amountInBaseCurrency;
    }

    @JsonProperty("currency")
    public String getCurrency() {
        return currency;
    }

    @JsonProperty("currency")
    public void setCurrency(String currency) {
        this.currency = currency;
    }

    @JsonProperty("date")
    public String getDate() {
        return date;
    }

    @JsonProperty("date")
    public void setDate(String date) {
        this.date = date;
    }

    @JsonProperty("dueDate")
    public String getDueDate() {
        return dueDate;
    }

    @JsonProperty("dueDate")
    public void setDueDate(String dueDate) {
        this.dueDate = dueDate;
    }

    @JsonProperty("entryNumber")
    public int getEntryNumber() {
        return entryNumber;
    }

    @JsonProperty("entryNumber")
    public void setEntryNumber(int entryNumber) {
        this.entryNumber = entryNumber;
    }

    @JsonProperty("text")
    public String getText() {
        return text;
    }

    @JsonProperty("text")
    public void setText(String text) {
        this.text = text;
    }

    @JsonProperty("entryType")
    public String getEntryType() {
        return entryType;
    }

    @JsonProperty("entryType")
    public void setEntryType(String entryType) {
        this.entryType = entryType;
    }

    @JsonProperty("vatAccount")
    public VatAccount getVatAccount() {
        return vatAccount;
    }

    @JsonProperty("vatAccount")
    public void setVatAccount(VatAccount vatAccount) {
        this.vatAccount = vatAccount;
    }

    @JsonProperty("voucherNumber")
    public int getVoucherNumber() {
        return voucherNumber;
    }

    @JsonProperty("voucherNumber")
    public void setVoucherNumber(int voucherNumber) {
        this.voucherNumber = voucherNumber;
    }

    @JsonProperty("invoiceNumber")
    public int getInvoiceNumber() {
        return invoiceNumber;
    }

    @JsonProperty("invoiceNumber")
    public void setInvoiceNumber(int invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }

    @JsonProperty("self")
    public String getSelf() {
        return self;
    }

    @JsonProperty("self")
    public void setSelf(String self) {
        this.self = self;
    }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperty(String name, Object value) {
        this.additionalProperties.put(name, value);
    }

    @Override
    public String toString() {
        return "Collection{" +
                "account=" + account +
                ", amount=" + amount +
                ", amountInBaseCurrency=" + amountInBaseCurrency +
                ", currency='" + currency + '\'' +
                ", date='" + date + '\'' +
                ", dueDate='" + dueDate + '\'' +
                ", entryNumber=" + entryNumber +
                ", text='" + text + '\'' +
                ", entryType='" + entryType + '\'' +
                ", vatAccount=" + vatAccount +
                ", voucherNumber=" + voucherNumber +
                ", invoiceNumber=" + invoiceNumber +
                ", self='" + self + '\'' +
                ", additionalProperties=" + additionalProperties +
                '}';
    }
}
