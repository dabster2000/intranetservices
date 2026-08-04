package dk.trustworks.intranet.aggregates.conference.resources;

import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the boundary check that stops an over-length submission from being acknowledged
 * with 204 and then silently dropped by the async projection.
 * <p>
 * Regression fixture: prod 2026-08-03, a 417-character contact-form message into
 * {@code andet varchar(255)}.
 */
class ParticipantFieldLimitsTest {

    private static MultivaluedMap<String, String> form(String... kv) {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.add(kv[i], kv[i + 1]);
        return m;
    }

    private static String text(int length) {
        return "x".repeat(length);
    }

    /** The exact message that was lost in production, reproduced at its real length. */
    private static final String LOST_MESSAGE = ("Hej Lars\n\nJeg er nyuddannet fra Digital Innovation & Management "
            + "på ITU og er meget interesseret i muligheden for at arbejde hos jer som IT-arkitekt.\n"
            + "Jeg er nysgerrig på at høre mere om stillingen og om, hvordan det er at starte ude hos jer. "
            + "Jeg sender også gerne en liste med mere konkrete spørgsmål, hvis det er nemmere.\n"
            + "Jeg håber, du har mulighed for at dele lidt om dine erfaringer.\n\nBedste hilsner\nAugust");

    @Test
    void messageLongerThanTheOldColumnIsAccepted() {
        // The 2026-08-03 submission: 255 < length <= MAX_MESSAGE. It must pass now that
        // V459 widened the column — rejecting it would just relocate the data loss.
        assertTrue(LOST_MESSAGE.length() > 255,
                "fixture must exceed the old varchar(255) bound to be a regression test");
        assertTrue(LOST_MESSAGE.length() <= ParticipantFieldLimits.MAX_MESSAGE);

        ConferenceParticipant p = ContactFormMapper.fromForm(
                form("name", "August", "email", "ahogsted@hotmail.com", "message", LOST_MESSAGE));

        assertNull(ParticipantFieldLimits.firstViolation(p),
                "a real contact-form message must not be rejected");
    }

    @Test
    void messageAtTheLimitIsAcceptedAndOneOverIsRejected() {
        ConferenceParticipant atLimit = new ConferenceParticipant();
        atLimit.setAndet(text(ParticipantFieldLimits.MAX_MESSAGE));
        assertNull(ParticipantFieldLimits.firstViolation(atLimit));

        ConferenceParticipant overLimit = new ConferenceParticipant();
        overLimit.setAndet(text(ParticipantFieldLimits.MAX_MESSAGE + 1));
        String violation = ParticipantFieldLimits.firstViolation(overLimit);
        assertNotNull(violation, "one character over the bound must be reported");
        assertTrue(violation.contains("message"), () -> "must name the offending field: " + violation);
    }

    @Test
    void shortFieldsAreBoundedToTheColumnWidth() {
        for (String field : new String[]{"name", "company", "title", "email"}) {
            ConferenceParticipant p = ContactFormMapper.fromForm(
                    form(field, text(ParticipantFieldLimits.MAX_SHORT_FIELD + 1)));
            String violation = ParticipantFieldLimits.firstViolation(p);
            assertNotNull(violation, () -> "over-length '" + field + "' must be reported");
            assertTrue(violation.contains(field), () -> "must name '" + field + "': " + violation);
        }
    }

    @Test
    void emptyAndNullSubmissionsAreNotViolations() {
        assertNull(ParticipantFieldLimits.firstViolation(null));
        assertNull(ParticipantFieldLimits.firstViolation(new ConferenceParticipant()));
        assertNull(ParticipantFieldLimits.firstViolation(ContactFormMapper.fromForm(form())));
    }

    @Test
    void unboundedFieldsBagIsNotRejected() {
        // The bag is persisted to a longtext column, so it has no width to overflow.
        ConferenceParticipant p = ContactFormMapper.fromForm(
                form("phone", text(5_000), "name", "August"));
        assertNull(ParticipantFieldLimits.firstViolation(p));
    }

    @Test
    void resourceRejectsOversizedSubmissionWith400() {
        // Drives the guard through the resource entry point. No collaborator is touched:
        // the check must run before the event is published, which is the whole point.
        ConferenceResource resource = new ConferenceResource();
        ConferenceParticipant p = new ConferenceParticipant();
        p.setAndet(text(ParticipantFieldLimits.MAX_MESSAGE + 1));

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> resource.rejectOversizedFields(p));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), e.getResponse().getStatus(),
                "an over-length submission must be a visible 400, not a silent 204");
        assertTrue(String.valueOf(e.getResponse().getEntity()).contains("message"),
                "the 400 body must tell the caller which field to shorten");
    }

    @Test
    void resourceAcceptsSubmissionWithinLimits() {
        ConferenceResource resource = new ConferenceResource();
        ConferenceParticipant p = ContactFormMapper.fromForm(
                form("name", "August", "email", "ahogsted@hotmail.com", "message", LOST_MESSAGE));

        assertDoesNotThrow(() -> resource.rejectOversizedFields(p));
    }
}
