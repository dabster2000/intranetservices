package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.TeamOverviewDTO.TeamRosterMemberDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Spec §4.6.0 / §4.6.7: the kind switch is derived from the roster and nothing else. */
class TeamKindResolverTest {

    private static TeamRosterMemberDTO member(String type) {
        return new TeamRosterMemberDTO("u-" + type, "A", "B", null, "ACTIVE", type, null, false,
                null, null, List.of(), null, null, null);
    }

    @Test
    void allStudentRosterIsHourly() {
        assertEquals(TeamKindResolver.HOURLY, TeamKindResolver.resolve(List.of(member("STUDENT"), member("STUDENT"))));
    }

    @Test
    void oneConsultantMakesItSalaried() {
        assertEquals(TeamKindResolver.SALARIED, TeamKindResolver.resolve(List.of(member("STUDENT"), member("CONSULTANT"))));
        assertEquals(TeamKindResolver.SALARIED, TeamKindResolver.resolve(List.of(member("STAFF"))));
    }

    @Test
    void emptyRosterIsTodaysView() {
        assertEquals(TeamKindResolver.SALARIED, TeamKindResolver.resolve(List.of()));
        assertEquals(TeamKindResolver.SALARIED, TeamKindResolver.resolve(null));
    }
}
