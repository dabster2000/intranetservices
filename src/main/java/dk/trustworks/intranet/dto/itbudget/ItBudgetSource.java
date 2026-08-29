package dk.trustworks.intranet.dto.itbudget;

/**
 * Why {@code totalBudget} has the value it has. The UI needs this to explain a
 * disabled "Add equipment" button rather than silently greying it out.
 */
public enum ItBudgetSource {

    /** A {@code team_settings.it_budget} row on one of the user's current MEMBER teams resolved it. */
    TEAM,

    /** No team row applied; {@code TeamSettingService.DEFAULT_IT_BUDGET} did. */
    DEFAULT,

    /** The user's current {@code userstatus.type} is STUDENT or EXTERNAL — no IT budget at all. */
    NO_BUDGET_CONSULTANT_TYPE
}
