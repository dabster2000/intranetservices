package dk.trustworks.intranet.aggregateservices.model;

import dk.trustworks.intranet.dto.KeyValueDTO;
import lombok.Data;

import java.time.LocalDate;
import java.util.Map;

@Data
public class ConsultantAggregateData {

    private String useruuid;
    private Map<LocalDate, KeyValueDTO> billableHoursPerMonth;
    private Map<LocalDate, KeyValueDTO> allHoursPerMonth;
    private Map<LocalDate, KeyValueDTO> registeredRevenuePerMonth;
    private Map<LocalDate, KeyValueDTO> registeredRevenueWithHelpedPerMonth;

    private Map<LocalDate, KeyValueDTO> salaryPerMonth;

}
