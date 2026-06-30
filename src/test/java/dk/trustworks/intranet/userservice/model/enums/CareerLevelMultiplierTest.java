package dk.trustworks.intranet.userservice.model.enums;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests — no Quarkus, no DB. Asserts the frozen multiplier map (§2.1)
 * for every one of the 20 {@link CareerLevel} values plus the null case.
 */
class CareerLevelMultiplierTest {

    @Test void nullLevel_isOne() {
        assertEquals(1.0, CareerLevelMultiplier.of(null));
    }

    // ---- 0× — NOT eligible ----

    @Test void associatePartner_isZero() {
        assertEquals(0.0, CareerLevelMultiplier.of(CareerLevel.ASSOCIATE_PARTNER));
    }

    @Test void partner_isZero() {
        assertEquals(0.0, CareerLevelMultiplier.of(CareerLevel.PARTNER));
    }

    @Test void director_isZero() {
        assertEquals(0.0, CareerLevelMultiplier.of(CareerLevel.DIRECTOR));
    }

    @Test void managingDirector_isZero() {
        assertEquals(0.0, CareerLevelMultiplier.of(CareerLevel.MANAGING_DIRECTOR));
    }

    @Test void managingPartner_isZero() {
        assertEquals(0.0, CareerLevelMultiplier.of(CareerLevel.MANAGING_PARTNER));
    }

    @Test void seniorManager_isZero() {
        assertEquals(0.0, CareerLevelMultiplier.of(CareerLevel.SENIOR_MANAGER));
    }

    // ---- 3× ----

    @Test void engagementDirector_isThree() {
        assertEquals(3.0, CareerLevelMultiplier.of(CareerLevel.ENGAGEMENT_DIRECTOR));
    }

    // ---- 2× ----

    @Test void manager_isTwo() {
        assertEquals(2.0, CareerLevelMultiplier.of(CareerLevel.MANAGER));
    }

    @Test void seniorEngagementManager_isTwo() {
        assertEquals(2.0, CareerLevelMultiplier.of(CareerLevel.SENIOR_ENGAGEMENT_MANAGER));
    }

    // ---- 1.5× ----

    @Test void engagementManager_isOneAndHalf() {
        assertEquals(1.5, CareerLevelMultiplier.of(CareerLevel.ENGAGEMENT_MANAGER));
    }

    // ---- 1× — default, eligible ----

    @Test void juniorConsultant_isOne() {
        assertEquals(1.0, CareerLevelMultiplier.of(CareerLevel.JUNIOR_CONSULTANT));
    }

    @Test void consultant_isOne() {
        assertEquals(1.0, CareerLevelMultiplier.of(CareerLevel.CONSULTANT));
    }

    @Test void professionalConsultant_isOne() {
        assertEquals(1.0, CareerLevelMultiplier.of(CareerLevel.PROFESSIONAL_CONSULTANT));
    }

    @Test void seniorConsultant_isOne() {
        assertEquals(1.0, CareerLevelMultiplier.of(CareerLevel.SENIOR_CONSULTANT));
    }

    @Test void leadConsultant_isOne() {
        assertEquals(1.0, CareerLevelMultiplier.of(CareerLevel.LEAD_CONSULTANT));
    }

    @Test void managingConsultant_isOne() {
        assertEquals(1.0, CareerLevelMultiplier.of(CareerLevel.MANAGING_CONSULTANT));
    }

    @Test void principalConsultant_isOne() {
        assertEquals(1.0, CareerLevelMultiplier.of(CareerLevel.PRINCIPAL_CONSULTANT));
    }

    @Test void associateManager_isOne() {
        assertEquals(1.0, CareerLevelMultiplier.of(CareerLevel.ASSOCIATE_MANAGER));
    }

    @Test void thoughtLeaderPartner_isOne() {
        assertEquals(1.0, CareerLevelMultiplier.of(CareerLevel.THOUGHT_LEADER_PARTNER));
    }

    @Test void practiceLeader_isOne() {
        assertEquals(1.0, CareerLevelMultiplier.of(CareerLevel.PRACTICE_LEADER));
    }

    // ---- Sanity: every value resolves into the allowed set, none throws ----

    @Test void everyValueResolvesIntoAllowedSet_andNoneThrows() {
        Set<Double> allowed = Set.of(0.0, 1.0, 1.5, 2.0, 3.0);
        for (CareerLevel level : CareerLevel.values()) {
            double mult = assertDoesNotThrow(() -> CareerLevelMultiplier.of(level), level.name());
            assertTrue(allowed.contains(mult),
                    () -> level.name() + " resolved to disallowed multiplier " + mult);
        }
    }

    @Test void enumHasExactlyTwentyValues() {
        assertEquals(20, CareerLevel.values().length);
    }
}
