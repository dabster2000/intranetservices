package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.TeamOverviewDTO.TeamRosterMemberDTO;

import java.util.List;

/**
 * Two kinds of team, one page (JK Team 2.0 WP6 §4.6.0).
 *
 * <p>{@code HOURLY} when every current MEMBER-role member has {@code userstatus.type = STUDENT};
 * otherwise {@code SALARIED}. Leaders are not members and do not count. Derived from the
 * roster the overview already builds — never a stored flag, because membership already says
 * it and a column could drift from it. An empty roster is {@code SALARIED} (today's view).
 */
public final class TeamKindResolver {

    public static final String SALARIED = "SALARIED";
    public static final String HOURLY = "HOURLY";
    public static final String STUDENT_TYPE = "STUDENT";

    private TeamKindResolver() {
    }

    public static String resolve(List<TeamRosterMemberDTO> roster) {
        if (roster == null || roster.isEmpty()) return SALARIED;
        for (TeamRosterMemberDTO m : roster) {
            if (!STUDENT_TYPE.equals(m.consultantType())) return SALARIED;
        }
        return HOURLY;
    }

    public static boolean isHourly(String kind) {
        return HOURLY.equals(kind);
    }
}
