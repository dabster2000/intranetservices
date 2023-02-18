package dk.trustworks.intranet.financeservice.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.financeservice.model.enums.ExcelFinanceType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import javax.persistence.*;
import javax.transaction.Transactional;
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

    public Finance() {
    }

    public Finance(LocalDate period, ExcelFinanceType expensetype, double amount) {
        this.uuid = UUID.randomUUID().toString();
        this.period = period;
        this.expensetype = expensetype;
        this.amount = amount;
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

    @Override
    public String toString() {
        return "Expense{" +
                "uuid='" + uuid + '\'' +
                ", period=" + period +
                ", expensetype=" + expensetype +
                ", amount=" + amount +
                '}';
    }
}
