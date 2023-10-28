package dk.trustworks.intranet.sales.model.enums;

public enum ConsultantCompetencies {
    BA("Business Analyst"), LA("Solution Architect"), PM("Project Manager"), SA("Tech - Software Architect"), DEV("Tech - Developer"), OPS("Tech - DevOps");

    private final String description;

    ConsultantCompetencies(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
