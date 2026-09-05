package dk.trustworks.intranet.aggregates.availability.config;

import dk.trustworks.intranet.aggregates.availability.config.DeclaredAvailabilityConfig.Mode;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code declaring = STUDENT ∧ day ≥ floor ∧ LIVE} — every leg of the conjunction, so the
 * cut-over cannot widen by accident (spec §4.1.3, D7; regression clause P4).
 */
class DeclaredAvailabilityPolicyTest {

    private static final LocalDate FLOOR = LocalDate.of(2026, 10, 1);

    @Test
    void live_student_onOrAfterFloor_isDeclaring() {
        assertTrue(DeclaredAvailabilityPolicy.declaring(Mode.LIVE, FLOOR, ConsultantType.STUDENT, FLOOR));
        assertTrue(DeclaredAvailabilityPolicy.declaring(Mode.LIVE, FLOOR, ConsultantType.STUDENT, FLOOR.plusDays(30)));
    }

    @Test
    void live_student_beforeFloor_keepsTodaysMaths() {
        // The history floor: nothing already computed moves on release day.
        assertFalse(DeclaredAvailabilityPolicy.declaring(Mode.LIVE, FLOOR, ConsultantType.STUDENT, FLOOR.minusDays(1)));
    }

    @Test
    void shadow_isNeverDeclaring_forAnyone() {
        for (ConsultantType type : ConsultantType.values()) {
            assertFalse(DeclaredAvailabilityPolicy.declaring(Mode.SHADOW, FLOOR, type, FLOOR.plusDays(10)),
                    "SHADOW must leave the resolver untouched for " + type);
        }
    }

    @Test
    void live_nonStudents_areNeverDeclaring() {
        // The declaring population is exactly STUDENT; CONSULTANT/STAFF/EXTERNAL keep allocation / 5.
        assertFalse(DeclaredAvailabilityPolicy.declaring(Mode.LIVE, FLOOR, ConsultantType.CONSULTANT, FLOOR.plusDays(1)));
        assertFalse(DeclaredAvailabilityPolicy.declaring(Mode.LIVE, FLOOR, ConsultantType.STAFF, FLOOR.plusDays(1)));
        assertFalse(DeclaredAvailabilityPolicy.declaring(Mode.LIVE, FLOOR, ConsultantType.EXTERNAL, FLOOR.plusDays(1)));
    }

    @Test
    void nulls_failClosed() {
        assertFalse(DeclaredAvailabilityPolicy.declaring(Mode.LIVE, FLOOR, null, FLOOR));
        assertFalse(DeclaredAvailabilityPolicy.declaring(Mode.LIVE, FLOOR, ConsultantType.STUDENT, null));
        assertFalse(DeclaredAvailabilityPolicy.declaring(Mode.LIVE, null, ConsultantType.STUDENT, FLOOR));
    }
}
