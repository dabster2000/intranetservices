package dk.trustworks.intranet.aggregates.accounting.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CompanyCategoryAmount {
    public String categoryCode;
    public String categoryName;
    public BigDecimal amount = BigDecimal.ZERO;

    public CompanyCategoryAmount() {}
    public CompanyCategoryAmount(String code, String name, BigDecimal amount) {
        this.categoryCode = code;
        this.categoryName = name;
        this.amount = amount;
    }
}
