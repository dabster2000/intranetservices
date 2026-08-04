package dk.trustworks.intranet.aggregates.conference.resources;

import dk.trustworks.intranet.exceptions.WebApplicationExceptionMapper;
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

    /**
     * A reconstruction of the message lost in production on 2026-08-03. The real payload
     * was 417 characters; this stands in at a representative length — what matters is that
     * it sits above the old varchar(255) bound and below the new one.
     */
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
    void fieldsBagIsBounded() {
        // The bag takes every unrecognised form key and lands in a longtext column, so
        // nothing in the database bounds it. On a @PermitAll endpoint that is a bulk-write
        // hole, and a big enough bag reproduces the original 204-then-lost failure.
        ConferenceParticipant ok = ContactFormMapper.fromForm(form("phone", "+4529654515"));
        assertNull(ParticipantFieldLimits.firstViolation(ok));

        ConferenceParticipant huge = ContactFormMapper.fromForm(
                form("name", "August", "junk", text(ParticipantFieldLimits.MAX_FIELDS_TOTAL + 1)));
        String violation = ParticipantFieldLimits.firstViolation(huge);
        assertNotNull(violation, "an oversized fields bag must be rejected");
        assertTrue(violation.contains("form fields"), () -> "must say what was too large: " + violation);
    }

    @Test
    void lengthIsCountedInCharactersNotUtf16Units() {
        // MariaDB bounds varchar by character. An emoji is 2 UTF-16 units but 1 character,
        // so counting units would reject a name the column would happily store.
        ConferenceParticipant p = new ConferenceParticipant();
        p.setName("🎉".repeat(200)); // 200 characters, 400 UTF-16 units
        assertEquals(400, p.getName().length(), "fixture must actually use surrogate pairs");
        assertNull(ParticipantFieldLimits.firstViolation(p),
                "200 characters fits varchar(255) and must not be rejected");

        ConferenceParticipant tooMany = new ConferenceParticipant();
        tooMany.setName("🎉".repeat(ParticipantFieldLimits.MAX_SHORT_FIELD + 1));
        assertNotNull(ParticipantFieldLimits.firstViolation(tooMany));
    }

    @Test
    void guardMustStayPrivateOrItBreaksTheAnonymousForms() throws Exception {
        // ConferenceResource carries a class-level @RolesAllowed({"conference:read"}).
        // Arc applies that to every non-private business method and intercepts via a bean
        // subclass, so even a self-call re-enters the security check. When this helper was
        // package-private, every anonymous POST to the @PermitAll public forms came back
        // 401 (verified against staging) instead of being accepted — a worse outage than
        // the silent data loss it was added to fix.
        var method = ConferenceResource.class.getDeclaredMethod(
                "rejectOversizedFields", ConferenceParticipant.class);
        assertTrue(java.lang.reflect.Modifier.isPrivate(method.getModifiers()),
                "rejectOversizedFields must be private: a non-private helper on this class "
                        + "inherits @RolesAllowed and turns anonymous submissions into 401s");
    }

    // ---- the guard must be wired into the paths the public form actually takes ----

    @Test
    void publicContactFormPathRejectsOversizedMessage() {
        // Drives the exact production entry point: receiveContactForm -> createParticipant
        // (2-arg) -> createParticipant (3-arg) -> rejectOversizedFields. The guard is the
        // first statement, so no injected collaborator is dereferenced. Without the call
        // on that path the submission would be published as an event and answered 204 --
        // the 2026-08-03 defect.
        ConferenceResource resource = new ConferenceResource();

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> resource.receiveContactForm("229fd5a2-9e6d-42eb-9afc-e3926286aebb",
                        form("name", "August", "email", "a@b.dk",
                                "message", text(ParticipantFieldLimits.MAX_MESSAGE + 1))));

        assertEquals(400, e.getResponse().getStatus());
    }

    @Test
    void updateParticipantPathRejectsOversizedMessage() {
        ConferenceResource resource = new ConferenceResource();
        ConferenceParticipant p = new ConferenceParticipant();
        p.setAndet(text(ParticipantFieldLimits.MAX_MESSAGE + 1));

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> resource.updateParticipantData("conf-uuid", p));
        assertEquals(400, e.getResponse().getStatus());
    }

    @Test
    void batchPhaseChangePathRejectsOversizedMessageAndNamesTheParticipant() {
        ConferenceResource resource = new ConferenceResource();
        ConferenceParticipant good = new ConferenceParticipant();
        good.setAndet("fine");
        ConferenceParticipant bad = new ConferenceParticipant();
        bad.setParticipantuuid("a57ad22e-f676-4c54-837e-bc8cd563586a");
        bad.setAndet(text(ParticipantFieldLimits.MAX_MESSAGE + 1));

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> resource.changeParticipantPhase("conf-uuid", 1, java.util.List.of(good, bad)));

        assertEquals(400, e.getResponse().getStatus());
        assertTrue(e.getMessage().contains("a57ad22e-f676-4c54-837e-bc8cd563586a"),
                () -> "an aborted batch must name the offending participant: " + e.getMessage());
    }

    @Test
    void resourceRejectsOversizedSubmissionWith400() {
        // Drives the guard through the resource entry point. No collaborator is touched:
        // the check must run before the event is published, which is the whole point.
        ConferenceResource resource = new ConferenceResource();
        ConferenceParticipant p = new ConferenceParticipant();
        p.setAndet(text(ParticipantFieldLimits.MAX_MESSAGE + 1));

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> resource.createParticipant("conf-uuid", 0, p));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), e.getResponse().getStatus(),
                "an over-length submission must be a visible 400, not a silent 204");

        // WebApplicationExceptionMapper drops the response entity and rebuilds the body
        // from getMessage(), so that is what the caller actually receives.
        Response mapped = new WebApplicationExceptionMapper().toResponse(e);
        assertEquals(400, mapped.getStatus());
        String body = String.valueOf(mapped.getEntity());
        assertTrue(body.contains("message"),
                () -> "the 400 body must name the offending field, got: " + body);
        assertTrue(body.contains(String.valueOf(ParticipantFieldLimits.MAX_MESSAGE)),
                () -> "the 400 body must state the limit, got: " + body);
    }

    @Test
    void resourceAcceptsSubmissionWithinLimits() {
        ConferenceResource resource = new ConferenceResource();
        ConferenceParticipant p = ContactFormMapper.fromForm(
                form("name", "August", "email", "ahogsted@hotmail.com", "message", LOST_MESSAGE));

        // Validation passes, so execution runs past the guard and fails on the collaborator
        // this bare instance has no CDI to inject. Any WebApplicationException here would
        // mean a real submission was turned away.
        Throwable t = assertThrows(Throwable.class, () -> resource.createParticipant("conf-uuid", 0, p));
        assertFalse(t instanceof WebApplicationException,
                () -> "a within-limits submission must not be rejected, got: " + t);
    }
}
