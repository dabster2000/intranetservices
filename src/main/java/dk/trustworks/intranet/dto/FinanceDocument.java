package dk.trustworks.intranet.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FinanceDocument {

    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate month;
    private double eSalaries;
    private double eEmployee_expenses;
    private double eHousing;
    private double eSales;
    private double eAdministration;
    private double eProduktion;

    public double sum() {
        return eSalaries+eEmployee_expenses+eHousing+eSales+eAdministration+eProduktion;
    }
}
