package dk.trustworks.intranet.aggregates.bonus.individual.exceptions;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IndividualBonusErrorResponse(
        String error,
        int status,
        String code,
        String field,
        LocalDate earningMonth,
        LocalDate payMonth,
        Boolean manualAction,
        BigDecimal expected,
        BigDecimal actual,
        List<String> violations
) {
}
