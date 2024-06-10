package dk.trustworks.intranet.userservice.model.enums;

import lombok.Getter;

@Getter
public enum SalarySupplementType {
    SUPPLEMENT("Salary supplement"), PREPAID("Prepaid bonus");

    private final String name;

    SalarySupplementType(String name) {
        this.name = name;
    }

}
