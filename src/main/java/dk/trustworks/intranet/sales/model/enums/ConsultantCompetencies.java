package dk.trustworks.intranet.sales.model.enums;

import lombok.Getter;

@Getter
public enum ConsultantCompetencies {
    BA("Business Analyst"), LA("Solution Architect"), PM("Project Manager"), SA("Tech - Software Architect"), CYB("Cyber Security Specialist"), DEV("Tech - Developer"), OPS("Tech - DevOps");

    private final String description;

    ConsultantCompetencies(String description) {
        this.description = description;
    }

}
