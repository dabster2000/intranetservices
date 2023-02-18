package dk.trustworks.intranet.achievementservice.model.enums;

/**
 * Speeddates med 10, 20, 30
 * 3, 4, 5 ugers sammenhængene ferie
 * 1 Orlov
 * 1 uges sygdom
 * 40, 50, 60 timers faktureret uge
 * Registrér 0 timer på en uge
 * 4 key purpose samtaler til tiden
 * 1 feriedag i alle årets måneder
 * Brugt uddannelsesbudgettet 1, 2, 3 år (med maks 500kr variation)
 * Har besøgt intra hver dag i 14 dage
 * Har udfyldt hele sit kompetenceskema
 * Har opdateret kompetenceskema 1, 2, 3 år i træk
 * Har lagt mere end 40, 50, 60 timer på backoffice opgaver på et år
 * Har arbejdet på mere end 5, 10, 15 kunder på et år
 * Har arbejdet på mere end 5, 10, 15 projekter på ét år
 */

public enum AchievementType {

    SPEEDDATES10("+10 Speeddates", 0, 1, "SPEEDDATES", "Speeddate with more than ten of your colleagues"),
    SPEEDDATES20("+20 Speeddates", 0, 2, "SPEEDDATES", "Speeddate with more than twenty of your colleagues"),
    SPEEDDATES30("+30 Speeddates", 0, 3, "SPEEDDATES", "Speeddate with more than thirty of your colleagues"),
    VACATION3("+3 weeks vacation", 1, 1, "VACATION", "You have held more than three weeks of vacation"),
    VACATION4("+4 weeks vacation", 1, 2, "VACATION", "You have held more than four weeks of vacation"),
    VACATION5("+5 weeks vacation", 1, 3, "VACATION", "You have held more than five weeks of vacation"),
    WORKWEEK40("+40 hours billable work", 2, 1, "WORKWEEK", ""),
    WORKWEEK50("+50 hours billable work", 2, 2, "WORKWEEK", ""),
    WORKWEEK60("+60 hours billable work", 2, 3, "WORKWEEK", ""),
    ANNIVERSARY3("3 years anniversary", 2, 1, "WORKWEEK", "Congratulations you been working at Trustworks for more than 3 years!"),
    ANNIVERSARY5("5 years anniversary", 2, 2, "WORKWEEK", "Congratulations you been working at Trustworks for more than 5 years!"),
    ANNIVERSARY10("10 years anniversary", 2, 3, "WORKWEEK", "Congratulations you been working at Trustworks for more than 10 years!"),
    MONTHVACATION("Vacation every month", 3, 1, "VACATIONMONTH", "You been on vacation for at least one day in every month during the years at Trustworks"),
    WEEKVACATION("Vacation every week", 3, 2, "VACATIONMONTH", "You been on vacation for at least one day in every week during the years at Trustworks"),
    CKOEXPENSE1("Used CKO budget 1 year", 4, 1, "CKOEXPENSE", "You have used every penny of your CKO budget"),
    CKOEXPENSE2("Used CKO budget 2 year", 4, 2, "CKOEXPENSE", "You have used every penny of your CKO budget for two years"),
    CKOEXPENSE3("Used CKO budget 3 year", 4, 3, "CKOEXPENSE", "You have used every penny of your CKO budget for three years"),
    INTRALOGIN14("Logged in to Intra 14 days in a row", 5, 1, "INTRALOGIN", ""),
    INTRALOGIN21("Logged in to Intra 21 days in a row", 5, 2, "INTRALOGIN", ""),
    INTRALOGIN28("Logged in to Intra 28 days in a row", 5, 3, "INTRALOGIN",""),
    AMBITIONENTERED("Completed skill charts", 6, 1, "AMBITIONENTERED", "You have filled in your skill chart"),
    BUDGETBEATER5("+5 Budget Beaters", 7, 1, "BUDGETBEATERS", "Your billable hours beats your budgettet hours for five months"),
    BUDGETBEATER15("+15 Budget Beaters", 7, 2, "BUDGETBEATERS", "Your billable hours beats your budgettet hours for fifteen months"),
    BUDGETBEATER30("+30 Budget Beaters", 7, 3, "BUDGETBEATERS", "Your billable hours beats your budgettet hours for thirty months"),
    BUBBLES3("+3 Bubble Member", 8, 1, "BUBBLES", "You are participating in more than 3 bubbles"),
    BUBBLES6("+6 Bubble Member", 8, 2, "BUBBLES", "You are participating in more than 6 bubbles"),
    BUBBLES9("+9 Bubble Member", 8, 3, "BUBBLES", "You are participating in more than 9 bubbles"),
    BUBBLELEADER("Bubble Leader", 9, 1, "BUBBLELEADER", "You have blown a bubble");

    private final String name;
    private final int number;
    private final int rank;
    private final String parent;
    private final String description;

    AchievementType(String s, int number, int rank, String parent, String description) {
        name = s;
        this.number = number;
        this.rank = rank;
        this.parent = parent;
        this.description = description;
    }

    public String getFilename() {
        return this.name().toLowerCase();
    }

    public int getNumber() {
        return number;
    }

    public String getParent() {
        return parent;
    }

    public int getRank() {
        return rank;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return this.name;
    }

}
