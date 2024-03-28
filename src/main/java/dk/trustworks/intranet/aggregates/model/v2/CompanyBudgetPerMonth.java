package dk.trustworks.intranet.aggregates.model.v2;

import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.model.Company;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@ToString(onlyExplicitlyIncluded = true)
@NoArgsConstructor
@AllArgsConstructor
public class CompanyBudgetPerMonth  {

    @ToString.Include
    private int year;
    @ToString.Include
    private int month;
    private Client client;
    private Company company;
    private Contract contract;
    @ToString.Include
    private double budgetHours;
    @ToString.Include
    private double expectedRevenue;
}
