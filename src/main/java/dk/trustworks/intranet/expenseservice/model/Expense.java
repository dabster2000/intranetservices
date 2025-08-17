package dk.trustworks.intranet.expenseservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import dk.trustworks.intranet.expenseservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.expenseservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "expenses")
public class Expense extends PanacheEntityBase {

    @Id
    private String uuid;
    private String useruuid;
    private Double amount;
    private String account;
    private String accountname;
    private String description = "";
    private String projectuuid = "";

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate datecreated;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate datemodified;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate expensedate;

    private String status;

    @Column(name = "paid_out")
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime paidOut;

    private boolean customerexpense;

    @Transient
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String expensefile;

    @JsonIgnore
    private int vouchernumber;

    @JsonIgnore
    private Integer journalnumber;

    @JsonIgnore
    private String accountingyear;

    public Expense() {
        uuid = UUID.randomUUID().toString();
        datecreated = LocalDate.now();
        datemodified = LocalDate.now();
    }

    public boolean isPaidOut() {
        return paidOut != null;
    }

    @Transient
    @JsonProperty("isLocked")
    public boolean isLocked() {
        return datecreated.isBefore(LocalDate.now());
    }
}
