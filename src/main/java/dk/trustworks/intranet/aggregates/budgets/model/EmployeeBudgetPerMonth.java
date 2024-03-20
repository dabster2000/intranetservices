package dk.trustworks.intranet.aggregates.budgets.model;

import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeBudgetPerMonth {

    private int year;
    private int month;
    private Client client;
    private User user;
    private Company company;
    private Contract contract;
    private double budgetHours;
    private double rate;
}
