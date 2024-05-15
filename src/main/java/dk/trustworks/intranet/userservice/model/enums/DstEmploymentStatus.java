package dk.trustworks.intranet.userservice.model.enums;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import lombok.Getter;

@Getter
public enum DstEmploymentStatus {
    STUDENT("Elever og lærlinge"),
    LEADER("Ledere og mellemledere"),
    SPECIAL_EMPLOYEES("Medarbejdere med særligt ansvar"),
    @JsonEnumDefaultValue EMPLOYEES("Øvrige/almindelige medarbejdere");

    private final String name;

    DstEmploymentStatus(String name) {
        this.name = name;
    }
}
