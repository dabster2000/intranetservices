package dk.trustworks.intranet.financeservice.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.financeservice.model.enums.ExcelFinanceType;
import dk.trustworks.intranet.financeservice.model.enums.PostingStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "finances")
public class Finance extends PanacheEntityBase {

    @Id
    private String uuid;


    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate period;

    @Enumerated(EnumType.STRING)
    private ExcelFinanceType expensetype;

    private double amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "postingstatus", nullable = false, length = 20)
    private PostingStatus postingstatus = PostingStatus.BOOKED;

    public Finance() {
    }

    public Finance(LocalDate period, ExcelFinanceType expensetype, double amount) {
        this(period, expensetype, amount, PostingStatus.BOOKED);
    }

    public Finance(LocalDate period, ExcelFinanceType expensetype, double amount, PostingStatus postingstatus) {
        this.uuid = UUID.randomUUID().toString();
        this.period = period;
        this.expensetype = expensetype;
        this.amount = amount;
        this.postingstatus = postingstatus == null ? PostingStatus.BOOKED : postingstatus;
    }

    public static List<Finance> findByPeriod(LocalDate period) {
        return find("period", period).list();
    }

    @Transactional
    public static void deleteByPeriod(LocalDate period) {
        delete("period", period);
    }

    public String getUuid() {
        return uuid;
    }

    public LocalDate getPeriod() {
        return period;
    }

    public void setPeriod(LocalDate period) {
        this.period = period;
    }

    public ExcelFinanceType getExpensetype() {
        return expensetype;
    }

    public void setExpensetype(ExcelFinanceType expensetype) {
        this.expensetype = expensetype;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public PostingStatus getPostingstatus() {
        return postingstatus;
    }

    public void setPostingstatus(PostingStatus postingstatus) {
        this.postingstatus = postingstatus == null ? PostingStatus.BOOKED : postingstatus;
    }

    @Override
    public String toString() {
        return "Expense{" +
                "uuid='" + uuid + '\'' +
                ", period=" + period +
                ", expensetype=" + expensetype +
                ", amount=" + amount +
                ", postingstatus=" + postingstatus +
                '}';
    }
}
