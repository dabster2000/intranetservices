package dk.trustworks.intranet.aggregates.users.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SalaryPayment {

    private LocalDate month;
    private String description;
    private String payment;

}
