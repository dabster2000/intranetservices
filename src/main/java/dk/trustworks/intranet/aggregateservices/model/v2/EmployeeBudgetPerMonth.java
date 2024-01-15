package dk.trustworks.intranet.aggregateservices.model.v2;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;

@Data
@Entity
@Table(name = "employee_budget_per_month")
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeBudgetPerMonth extends PanacheEntityBase {

    @Id
    @JsonIgnore
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private int id;

    private int year;
    private int month;

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
    private double rate;
}
