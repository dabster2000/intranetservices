package dk.trustworks.intranet.aggregates.model.v2;

import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.model.Company;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanyBudgetPerMonth  {

    private int year;
    private int month;

    private Client client;

    private Company company;

    private Contract contract;
    private double budgetHours;
    private double expectedRevenue;
}
