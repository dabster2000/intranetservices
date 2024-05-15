package dk.trustworks.intranet.userservice.model.enums;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import lombok.Getter;

@Getter
public enum DstEmploymentFunction {

    CEO("CEO", 112010),
    CXO("COO", 112010),
    CCuO("Ledelse inden for HR-funktioner", 121200),
    CMO("Ledelse inden for marketing", 122100),
    MARKETING("Arbejde inden for marketing", 243100),
    @JsonEnumDefaultValue MANAGEMENT_CONSULTANT("IT-Projektstyring", 251210),
    DEVELOPER("Rådgivning og programmering inden for softwareudvikling", 251220),
    HR_OFFICE_WORK("Almindeligt kontorarbejde inden for HR", 441600),
    OFFICE_WORK("Almindeligt kontorarbejde", 411000),
    ;
    private final String name;
    private final int code;

    DstEmploymentFunction(String name, int code) {
        this.name = name;
        this.code = code;
    }
}
