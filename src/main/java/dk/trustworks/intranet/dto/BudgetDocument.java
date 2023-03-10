package dk.trustworks.intranet.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.userservice.model.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "budget_document")
@NoArgsConstructor
@AllArgsConstructor
public class BudgetDocument {

    @Id
    @JsonIgnore
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private int id;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate month;

    @OneToOne
    @JoinColumn(name = "clientuuid")
    private Client client;
    @OneToOne
    @JoinColumn(name = "useruuid")
    private User user;
    @OneToOne
    @JoinColumn(name = "contractuuid")
    private Contract contract;
    private double budgetHours;
    private double budgetHoursWithNoAvailabilityAdjustment;
    private double rate;

    public BudgetDocument(LocalDate month, Client client, User user, Contract contract, double budgetHours, double budgetHoursWithNoAvailabilityAdjustment, double rate) {
        this.month = month;
        this.client = client;
        this.user = user;
        this.contract = contract;
        this.budgetHours = budgetHours;
        this.budgetHoursWithNoAvailabilityAdjustment = budgetHoursWithNoAvailabilityAdjustment;
        this.rate = rate;
    }
}
