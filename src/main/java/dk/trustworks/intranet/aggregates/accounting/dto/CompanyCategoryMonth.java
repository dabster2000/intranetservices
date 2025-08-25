package dk.trustworks.intranet.aggregates.accounting.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
public class CompanyCategoryMonth {
    public LocalDate month; // first day of month
    public List<CompanyCategoryAmount> categories = new ArrayList<>();

    public CompanyCategoryMonth() {}
    public CompanyCategoryMonth(LocalDate month) {
        this.month = month;
    }
}
