package dk.trustworks.intranet.financeservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.model.Company;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDate;

@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Data
@NoArgsConstructor
@Entity
@Table(name = "finance_details")
public class FinanceDetails extends PanacheEntityBase {

    @Id
    @JsonIgnore
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "companyuuid")
    private Company company;

    private int entrynumber;

    private int accountnumber;

    private int invoicenumber;

    private double amount;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate expensedate;

    private String text;

    public FinanceDetails(Company company, int entrynumber, int accountnumber, int invoicenumber, double amount, LocalDate expensedate, String text) {
        this.company = company;
        this.invoicenumber = invoicenumber;
        this.id = id;
        this.entrynumber = entrynumber;
        this.accountnumber = accountnumber;
        this.amount = amount;
        this.expensedate = expensedate;
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
