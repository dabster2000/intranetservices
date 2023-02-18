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
        "customerInvoiceNumber",
        "date"
})
public class ManualCustomerInvoice {

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
    @JsonProperty("customerInvoiceNumber")
    public int customerInvoiceNumber;
  //  @JsonProperty("journalEntryNumber")
  //  public int journalEntryNumber;

    public ManualCustomerInvoice(){

    }
    public ManualCustomerInvoice(ExpenseAccount account, int customerInvoiceNumber, String text, double amount, ContraAccount contraAccount, String date) {
        this.account = account;
        this.customerInvoiceNumber = customerInvoiceNumber;
        this.text = text;
        this.amount = amount;
        this.contraAccount = contraAccount;
        this.date = date;
    //    this.journalEntryNumber = journalEntryNumber;
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

    @JsonProperty("customerInvoiceNumber")
    public int getCustomerInvoiceNumber() {
        return customerInvoiceNumber;
    }

    @JsonProperty("customerInvoiceNumber")
    public void setCustomerInvoiceNumber(int customerInvoiceNumber) {
        this.customerInvoiceNumber = customerInvoiceNumber;
    }

    /*
    @JsonProperty("journalEntryNumber")
    public int getJournalEntryNumber() {
        return journalEntryNumber;
    }

    @JsonProperty("journalEntryNumber")
    public void setJournalEntryNumber(int journalEntryNumber) {
        this.journalEntryNumber = journalEntryNumber;
    }
    */

    @Override
    public String toString() {
        return "FinanceVoucher{" +
                "account='" + account + '\'' +
                ", text='" + text + '\'' +
                ", amount='" + amount + '\'' +
                ", contraAccount='" + contraAccount + '\'' +
                ", date='" + date +
                '}';
    }
}