package dk.trustworks.intranet.recruitmentservice.events;

import org.junit.jupiter.api.Test;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions.PII_SENTINEL;
import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions.assertNoPiiInPayload;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Self-tests for the shared PII-boundary fixture (P1 DoD: the fixture
 * exists and is wired into shared test support). Every later phase runs its
 * emitted events through this fixture.
 */
class RecruitmentEventPiiAssertionsTest {

    // ---- clean payloads pass --------------------------------------------

    @Test
    void structuralPayload_passes() {
        assertDoesNotThrow(() -> assertNoPiiInPayload(
                "{\"stage\":\"SCREENING\",\"direction\":\"FORWARD\",\"stage_count\":5,"
                        + "\"template_id\":\"9a1b\",\"reason_code\":\"FILLED\"}"));
    }

    @Test
    void nullPayload_passes() {
        assertDoesNotThrow(() -> assertNoPiiInPayload((String) null));
    }

    @Test
    void structuralNameLikeKeys_pass() {
        // *_name keys that are structural by convention (reactor_name,
        // template_name) are allowed — only exact forbidden keys trip.
        assertDoesNotThrow(() -> assertNoPiiInPayload(
                "{\"reactor_name\":\"slack\",\"template_name\":\"standard-4\"}"));
    }

    // ---- forbidden keys fail --------------------------------------------

    @Test
    void forbiddenKey_topLevel_fails() {
        AssertionError e = assertThrows(AssertionError.class,
                () -> assertNoPiiInPayload("{\"email\":\"x\"}"));
        assertTrue(e.getMessage().contains("email"));
    }

    @Test
    void forbiddenKey_nested_fails() {
        assertThrows(AssertionError.class,
                () -> assertNoPiiInPayload("{\"meta\":{\"first_name\":\"x\"}}"));
    }

    @Test
    void forbiddenKey_insideArray_fails() {
        assertThrows(AssertionError.class,
                () -> assertNoPiiInPayload("{\"items\":[{\"salary\":50000}]}"));
    }

    @Test
    void forbiddenKey_caseInsensitive_fails() {
        assertThrows(AssertionError.class,
                () -> assertNoPiiInPayload("{\"Email\":\"x\"}"));
    }

    // ---- suspicious values fail ------------------------------------------

    @Test
    void emailShapedValue_fails_withoutEchoingTheValue() {
        AssertionError e = assertThrows(AssertionError.class,
                () -> assertNoPiiInPayload("{\"ref\":\"jane.doe@example.com\"}"));
        assertFalse(e.getMessage().contains("jane.doe"),
                "assertion message must not copy the potential PII value into logs");
    }

    @Test
    void cprShapedValue_fails() {
        assertThrows(AssertionError.class,
                () -> assertNoPiiInPayload("{\"ref\":\"010203-1234\"}"));
    }

    @Test
    void sentinelValue_fails() {
        assertThrows(AssertionError.class,
                () -> assertNoPiiInPayload("{\"ref\":\"xx" + PII_SENTINEL + "yy\"}"));
    }

    @Test
    void nestedSentinel_deepInArrays_fails() {
        assertThrows(AssertionError.class,
                () -> assertNoPiiInPayload("{\"a\":[[{\"b\":\"" + PII_SENTINEL + " Hansen\"}]]}"));
    }

    // ---- malformed payloads fail ------------------------------------------

    @Test
    void invalidJson_fails() {
        assertThrows(AssertionError.class, () -> assertNoPiiInPayload("{not json"));
    }

    // ---- entity overload: envelope consistency ----------------------------

    @Test
    void entityOverload_checksPiiStateConsistency() {
        RecruitmentEvent event = new RecruitmentEvent();
        event.eventType = RecruitmentEventType.NOTE_ADDED;
        event.payload = "{\"private\":true}";
        event.pii = "{\"note_text\":\"hello\"}";
        event.piiState = RecruitmentPiiState.NONE; // inconsistent on purpose
        assertThrows(AssertionError.class, () -> assertNoPiiInPayload(event));

        event.piiState = RecruitmentPiiState.PRESENT;
        assertDoesNotThrow(() -> assertNoPiiInPayload(event));

        event.pii = null;
        assertThrows(AssertionError.class, () -> assertNoPiiInPayload(event),
                "PRESENT without a pii section is inconsistent");

        event.piiState = RecruitmentPiiState.NONE;
        assertDoesNotThrow(() -> assertNoPiiInPayload(event));
    }
}
