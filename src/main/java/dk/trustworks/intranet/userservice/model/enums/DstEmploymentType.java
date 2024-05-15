package dk.trustworks.intranet.userservice.model.enums;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import lombok.Getter;

@Getter
public enum DstEmploymentType {
    @JsonEnumDefaultValue NOT_LIMITED("Ikke tidsbegrænset"), LIMITED("Tidsbegrænset");

    private final String name;

    DstEmploymentType(String name) {
        this.name = name;
    }

}
