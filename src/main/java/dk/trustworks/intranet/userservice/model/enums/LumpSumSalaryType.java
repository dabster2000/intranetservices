package dk.trustworks.intranet.userservice.model.enums;

import lombok.Getter;

@Getter
public enum LumpSumSalaryType {

    TW_BONUS(41, "Din del af Trustworks"), TEAMLEAD_BONUS(41, "Teamlead bonus"), COMMERCIAL_PARTNER_BONUS(41, "Partner bonus"), TEAM_SPLIT_BONUS(41, "Team split bonus"), PROD_BONUS(41, "Production bonus"),
    // Individual, per-employee production bonus (declarative individual_bonus_rule). Distinct from
    // PROD_BONUS (partner-bonus) so reporting/dedup is unambiguous; same Danløn behaviour (type 41). (D4)
    INDIVIDUAL_PROD_BONUS(41, "Individual production bonus"),
    OTHER(41, "Other income");

    private final int danloenType;
    private final String name;

    LumpSumSalaryType(int danloenType, String name) {
        this.danloenType = danloenType;
        this.name = name;
    }

}
