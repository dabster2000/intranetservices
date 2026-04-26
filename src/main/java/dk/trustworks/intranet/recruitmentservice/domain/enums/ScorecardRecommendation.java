package dk.trustworks.intranet.recruitmentservice.domain.enums;

public enum ScorecardRecommendation {
    STRONG_HIRE, HIRE, LEAN_HIRE, LEAN_NO, NO_HIRE;

    /** Map to numeric 1-5 for composite scoring (5 = STRONG_HIRE, 1 = NO_HIRE). */
    public int toNumeric() {
        return switch (this) {
            case STRONG_HIRE -> 5;
            case HIRE -> 4;
            case LEAN_HIRE -> 3;
            case LEAN_NO -> 2;
            case NO_HIRE -> 1;
        };
    }
}
