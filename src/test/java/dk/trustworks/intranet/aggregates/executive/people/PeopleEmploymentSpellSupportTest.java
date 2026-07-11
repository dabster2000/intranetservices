package dk.trustworks.intranet.aggregates.executive.people;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static dk.trustworks.intranet.aggregates.executive.people.PeopleEmploymentSpellSupport.StatusKind.EMPLOYED;
import static dk.trustworks.intranet.aggregates.executive.people.PeopleEmploymentSpellSupport.StatusKind.TERMINATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeopleEmploymentSpellSupportTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final LocalDate CHANGE = LocalDate.of(2025, 6, 1);
    private static final LocalDate END = LocalDate.of(2025, 12, 1);

    @Test
    void crossCompanyDanlonPairStaysContinuousRegardlessOfUuidLexicalOrder() {
        List<PeopleEmploymentSpellSupport.Spell> expected = List.of(
                new PeopleEmploymentSpellSupport.Spell(START, END));

        assertEquals(expected, PeopleEmploymentSpellSupport.calculate(crossCompanyPoints("z-term", "a-active")));
        assertEquals(expected, PeopleEmploymentSpellSupport.calculate(crossCompanyPoints("a-term", "z-active")));
    }

    @Test
    void sameCompanyTerminationThenActiveStartsNewSpellRegardlessOfUuidLexicalOrder() {
        List<PeopleEmploymentSpellSupport.Spell> expected = List.of(
                new PeopleEmploymentSpellSupport.Spell(START, CHANGE),
                new PeopleEmploymentSpellSupport.Spell(CHANGE, END));

        assertEquals(expected, PeopleEmploymentSpellSupport.calculate(sameCompanyRehirePoints("z-term", "a-active")));
        assertEquals(expected, PeopleEmploymentSpellSupport.calculate(sameCompanyRehirePoints("a-term", "z-active")));
    }

    @Test
    void legacyEqualAuditTimeRehireIsIndependentOfUuidLexicalOrder() {
        List<PeopleEmploymentSpellSupport.Spell> expected = List.of(
                new PeopleEmploymentSpellSupport.Spell(START, CHANGE),
                new PeopleEmploymentSpellSupport.Spell(CHANGE, END));

        assertEquals(expected, PeopleEmploymentSpellSupport.calculate(
                sameCompanyRehirePointsAtEqualAuditTime("z-term", "a-active")));
        assertEquals(expected, PeopleEmploymentSpellSupport.calculate(
                sameCompanyRehirePointsAtEqualAuditTime("a-term", "z-active")));
    }

    @Test
    void laterSameCompanyTerminationRemainsAGenuineExit() {
        List<PeopleEmploymentSpellSupport.StatusPoint> points = List.of(
                point(START, EMPLOYED, "company-a", 8, "start"),
                point(CHANGE, EMPLOYED, "company-a", 10, "z-active"),
                point(CHANGE, TERMINATED, "company-a", 11, "a-term"));

        assertEquals(List.of(new PeopleEmploymentSpellSupport.Spell(START, CHANGE)),
                PeopleEmploymentSpellSupport.calculate(points));
    }

    @Test
    void sqlUsesBusinessPairFlagsBeforeAuditAndUuidFallbacks() {
        String sql = PeopleEmploymentSpellSupport.sqlCtes();

        assertTrue(sql.contains("transfer_destination DESC,sdc.same_company_rehire DESC,"));
        assertTrue(sql.contains("same_company_rehire=1"));
        assertTrue(sql.contains("LEAD(sb.start_date)"));
        assertTrue(sql.contains("next_start_date<raw_end_date"));
    }

    private static List<PeopleEmploymentSpellSupport.StatusPoint> crossCompanyPoints(
            String terminatedUuid, String activeUuid) {
        return List.of(
                point(START, EMPLOYED, "company-a", 8, "start"),
                point(CHANGE, TERMINATED, "company-a", 10, terminatedUuid),
                point(CHANGE, EMPLOYED, "company-b", 10, activeUuid),
                point(END, TERMINATED, "company-b", 8, "end"));
    }

    private static List<PeopleEmploymentSpellSupport.StatusPoint> sameCompanyRehirePoints(
            String terminatedUuid, String activeUuid) {
        return List.of(
                point(START, EMPLOYED, "company-a", 8, "start"),
                point(CHANGE, TERMINATED, "company-a", 10, terminatedUuid),
                point(CHANGE, EMPLOYED, "company-a", 11, activeUuid),
                point(END, TERMINATED, "company-a", 8, "end"));
    }

    private static List<PeopleEmploymentSpellSupport.StatusPoint> sameCompanyRehirePointsAtEqualAuditTime(
            String terminatedUuid, String activeUuid) {
        return List.of(
                point(START, EMPLOYED, "company-a", 8, "start"),
                point(CHANGE, TERMINATED, "company-a", 10, terminatedUuid),
                point(CHANGE, EMPLOYED, "company-a", 10, activeUuid),
                point(END, TERMINATED, "company-a", 8, "end"));
    }

    private static PeopleEmploymentSpellSupport.StatusPoint point(
            LocalDate date,
            PeopleEmploymentSpellSupport.StatusKind kind,
            String company,
            int hour,
            String uuid) {
        return new PeopleEmploymentSpellSupport.StatusPoint(
                date, kind, company, LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)), uuid);
    }
}
