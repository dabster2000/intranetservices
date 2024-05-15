package dk.trustworks.intranet.aggregates.accounting.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DanlonSalarySupplements {
    private String cvr;
    private String danlonNumber;
    private String name;
    private String salaryType;
    private String description;
    private String units;
    private String price;
    private String amount;
}
