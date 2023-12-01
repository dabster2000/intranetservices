package dk.trustworks.intranet.aggregateservices.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import javax.transaction.Transactional;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "budget_document")
@NoArgsConstructor
@AllArgsConstructor
public class BudgetDocumentPerDay extends PanacheEntityBase {

    @Id
    @JsonIgnore
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private int id;

    @JsonProperty("documentDate")
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    @Column(name = "document_date")
    private LocalDate documentDate; // done

    private int year;
    private int month;
    private int day;

    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "clientuuid")
    private Client client;

    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "useruuid")
    private User user;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "companyuuid")
    private Company company;

    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "contractuuid")
    private Contract contract;
    private double budgetHours;
    private double budgetHoursWithNoAvailabilityAdjustment;
    private double rate;

    public BudgetDocumentPerDay(LocalDate documentDate, Client client, User user, Contract contract, double budgetHours, double budgetHoursWithNoAvailabilityAdjustment, double rate) {
        this.documentDate = documentDate;
        this.year = documentDate.getYear();
        this.month = documentDate.getMonthValue();
        this.day = documentDate.getDayOfMonth();
        this.client = client;
        this.user = user;
        this.contract = contract;
        this.budgetHours = budgetHours;
        this.budgetHoursWithNoAvailabilityAdjustment = budgetHoursWithNoAvailabilityAdjustment;
        this.rate = rate;
    }

    @Transactional
    public void truncate() {
        BudgetDocumentPerDay.deleteAll();
    }
}
