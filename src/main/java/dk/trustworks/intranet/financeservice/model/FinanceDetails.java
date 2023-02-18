package dk.trustworks.intranet.financeservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import javax.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "finance_details")
public class FinanceDetails extends PanacheEntityBase {

    @Id
    @JsonIgnore
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private int id;

    private int entrynumber;

    private int accountnumber;

    private int invoicenumber;

    private double amount;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate expensedate;

    private String text;

    public FinanceDetails() {
    }

    public FinanceDetails(int entrynumber, int accountnumber, int invoicenumber, double amount, LocalDate expensedate, String text) {
        this.invoicenumber = invoicenumber;
        this.id = id;
        this.entrynumber = entrynumber;
        this.accountnumber = accountnumber;
        this.amount = amount;
        this.expensedate = expensedate;
        this.text = text;
    }
/*
    public static List<ExpenseDetails> findByExpensedateAndAccountnumberInOrderByAmountDesc(LocalDate expensedate, int... accountnumber) {

    }
*/

    public int getInvoicenumber() {
        return invoicenumber;
    }

    public void setInvoicenumber(int invoicenumber) {
        this.invoicenumber = invoicenumber;
    }

    public int getId() {
        return id;
    }

    public int getEntrynumber() {
        return entrynumber;
    }

    public void setEntrynumber(int entrynumber) {
        this.entrynumber = entrynumber;
    }

    public int getAccountnumber() {
        return accountnumber;
    }

    public void setAccountnumber(int accountnumber) {
        this.accountnumber = accountnumber;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public LocalDate getExpensedate() {
        return expensedate;
    }

    public void setExpensedate(LocalDate expensedate) {
        this.expensedate = expensedate;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    @Override
    public String toString() {
        return "ExpenseDetailed{" +
                "id=" + id +
                ", entrynumber=" + entrynumber +
                ", accountnumber=" + accountnumber +
                ", amount=" + amount +
                ", expensedate=" + expensedate +
                ", text='" + text + '\'' +
                '}';
    }
}
