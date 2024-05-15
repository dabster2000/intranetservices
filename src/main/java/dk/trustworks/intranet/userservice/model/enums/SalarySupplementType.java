package dk.trustworks.intranet.userservice.model.enums;

import lombok.Getter;

@Getter
public enum SalarySupplementType {
    AMOUNT("Fixed amount"), PERCENTAGE("Percentage");

    private final String name;

    SalarySupplementType(String name) {
        this.name = name;
    }

}
