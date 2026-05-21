package dk.trustworks.intranet.financeservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.financeservice.model.enums.PostingStatus;
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

    private double remainder;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate expensedate;

    private String text;

    @Enumerated(EnumType.STRING)
    @Column(name = "postingstatus", nullable = false, length = 20)
    private PostingStatus postingstatus = PostingStatus.BOOKED;

    private int journalnumber;

    private int vouchernumber;

    private String objectversion;

    private Integer entrytypenumber;

    private String currency;

    private Double exchangerate;

    public FinanceDetails(Company company, int entrynumber, int accountnumber, int invoicenumber, double amount, double remainder, LocalDate expensedate, String text) {
        this(company, entrynumber, accountnumber, invoicenumber, amount, remainder, expensedate, text,
                PostingStatus.BOOKED, 0, 0, null, null, null, null);
    }

    public FinanceDetails(Company company, int entrynumber, int accountnumber, int invoicenumber, double amount, double remainder, LocalDate expensedate, String text,
                          PostingStatus postingstatus, int journalnumber, int vouchernumber, String objectversion,
                          Integer entrytypenumber, String currency, Double exchangerate) {
        this.company = company;
        this.invoicenumber = invoicenumber;
        this.entrynumber = entrynumber;
        this.accountnumber = accountnumber;
        this.amount = amount;
        this.remainder = remainder;
        this.expensedate = expensedate;
        this.text = text;
        this.postingstatus = postingstatus == null ? PostingStatus.BOOKED : postingstatus;
        this.journalnumber = journalnumber;
        this.vouchernumber = vouchernumber;
        this.objectversion = objectversion;
        this.entrytypenumber = entrytypenumber;
        this.currency = currency;
        this.exchangerate = exchangerate;
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
