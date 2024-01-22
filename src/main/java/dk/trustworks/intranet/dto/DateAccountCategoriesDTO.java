package dk.trustworks.intranet.dto;

import dk.trustworks.intranet.financeservice.model.AccountingCategory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DateAccountCategoriesDTO {

    private LocalDate date;
    private List<AccountingCategory> accountingCategories = new ArrayList<>();

}
