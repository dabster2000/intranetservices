package dk.trustworks.intranet.messaging.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;

import java.time.LocalDate;

@Data
@RegisterForReflection
public class DateRangeMap {
    public LocalDate fromDate;
    public LocalDate endDate;

    public DateRangeMap() {
    }

    public DateRangeMap(LocalDate fromDate, LocalDate endDate) {
        this.fromDate = fromDate;
        this.endDate = endDate;
    }
}
