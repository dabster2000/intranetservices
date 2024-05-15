package dk.trustworks.intranet.userservice.model.enums;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import lombok.Getter;

@Getter
public enum DstSalaryType {
    @JsonEnumDefaultValue MONTHLY_PAY("Fast løn uden overbejdsbetaling"),
    HOURLY_PAY("Timeløn");

    private final String name;

    DstSalaryType(String name) {
        this.name = name;
    }
}
