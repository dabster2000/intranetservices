package dk.trustworks.intranet.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "availability_document")
@NoArgsConstructor
@AllArgsConstructor
public class AvailabilityPerDayDocument extends PanacheEntityBase {

    @Id
    @JsonIgnore
    //@GeneratedValue(strategy= GenerationType.IDENTITY)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seqGen")
    @SequenceGenerator(name = "seqGen", sequenceName = "availability_id_seq", allocationSize = 1)
    private int id;

    @JsonProperty("month")
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate month;

    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "useruuid")
    @JsonProperty("user")
    private User user;

    @Column(name = "gross_available_hours")
    @JsonProperty("grossAvailableHours")
    private Double grossAvailableHours; // Total availability i henhold til ansættelseskontrakt, f.eks. 37 timer.

    @Column(name = "vacation_hours")
    @JsonProperty("vacationHours")
    private Double vacationHours;

    @Column(name = "sick_hours")
    @JsonProperty("sickHours")
    private Double sickHours;

    @Column(name = "maternity_leave_hours")
    @JsonProperty("maternityLeaveHours")
    private Double maternityLeaveHours;

    @Column(name = "non_payd_leave_hours")
    @JsonProperty("nonPaydLeaveHours")
    private Double nonPaydLeaveHours;

    @Column(name = "paid_leave_hours")
    @JsonProperty("paidLeaveHours")
    private Double paidLeaveHours;

    @Column(name = "consultant_type")
    @JsonProperty("consultantType")
    @Enumerated(EnumType.STRING)
    private ConsultantType consultantType;

    @Column(name = "status_type")
    @JsonProperty("statusType")
    @Enumerated(EnumType.STRING)
    private StatusType statusType;

    @Transient
    @JsonProperty("netAvailableHours")
    private Double netAvailableHours; // Det antal timer, som konsulenten er tilgængelig, minus de to timer der bruges om fredagen samt eventuelt ferie og sygdom.

    public AvailabilityPerDayDocument(LocalDate month, User user, Double grossAvailableHours, Double vacationHours, Double sickHours, Double maternityLeaveHours, Double nonPaydLeaveHours, Double paidLeaveHours, ConsultantType consultantType, StatusType statusType) {
        this.month = month;
        this.user = user;
        this.grossAvailableHours = grossAvailableHours;
        this.vacationHours = vacationHours;
        this.sickHours = sickHours;
        this.maternityLeaveHours = maternityLeaveHours;
        this.nonPaydLeaveHours = nonPaydLeaveHours;
        this.paidLeaveHours = paidLeaveHours;
        this.consultantType = consultantType;
        this.statusType = statusType;
    }

    @Transient
    public Double getNetAvailableHours() {
        return Math.max(grossAvailableHours - vacationHours - sickHours - maternityLeaveHours - nonPaydLeaveHours - paidLeaveHours, 0.0);
    }

    @Override
    public String toString() {
        return "AvailabilityPerDayDocument{" +
                "month=" + month +
                ", grossAvailableHours=" + grossAvailableHours +
                ", vacationHours=" + vacationHours +
                ", sickHours=" + sickHours +
                ", maternityLeaveHours=" + maternityLeaveHours +
                ", nonPaydLeaveHoursPerday=" + nonPaydLeaveHours +
                ", paidLeaveHoursPerDay=" + paidLeaveHours +
                ", consultantType=" + consultantType +
                ", statusType=" + statusType +
                ", netAvailableHours=" + getNetAvailableHours() +
                '}';
    }
}
