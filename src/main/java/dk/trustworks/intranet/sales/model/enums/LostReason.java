package dk.trustworks.intranet.sales.model.enums;

public enum LostReason {

    INTERNAL_CANDIDATE("Kunden valgte en intern kandidat"),
    LOST_TO_COMPETITION("Kunden valgte en anden til opgaven"),
    WITHDRAWN("Kunde trak opgaven / tilbuddet tilbage"),
    BAD_TERMS("Vi bød ikke grundet vilkår fx lav timepris"),
    MISSING_SKILLS("Vi bød ikke fordi vi ikke kunne matche kundens ønske om kompetencer"),
    OTHER("Vi bød ikke pga andre årsdager"),
    NO_RESOURCES("Vi bød ikke fordi vi manglede resourcer");

    private final String description;

    LostReason(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
