package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailBodyFormat;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.enterprise.context.ContextNotActiveException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The four merge tokens the renderer deliberately does NOT resolve:
 * {@code visiting_address}, {@code company_name}, {@code recruiter_name} and
 * {@code recruiter_email}. They are lookups, so
 * {@link RecruitmentEmailService#standardExtras()} supplies them through the
 * renderer's existing extras seam and the renderer stays a pure function.
 * <p>
 * Two things are being pinned. First, that all four actually resolve — the
 * renderer leaves an unknown token VERBATIM, so a token nobody supplies is
 * not a blank in the letter, it is a literal
 * <code>{{recruiter_name}}</code> in a candidate's inbox. Second, that
 * {@code recruiter_*} come out EMPTY rather than missing on an automatic
 * send, which is the only path where no human acted.
 * <p>
 * Database-free: the two collaborators are injected fields, and the only
 * Panache read ({@code User.findById}) is mocked statically.
 */
class RecruitmentEmailMergeVocabularyTest {

    private static final String ADDRESS = "Hausergade 3, 1128 København K";
    private static final String ACTOR_UUID = "9f1c5f0a-actor";

    /** Every token this test cares about, in one body. */
    private static final String BODY = "Kom forbi {{visiting_address}}. "
            + "Venlig hilsen {{recruiter_name}} ({{recruiter_email}}), {{company_name}}";

    private RecruitmentEmailService service;
    private RecruitmentVisitingAddress addressSetting;

    /** The row {@link #actorRow()} hands back for {@link #ACTOR_UUID}. */
    private User actor;

    @BeforeEach
    void setUp() {
        addressSetting = mock(RecruitmentVisitingAddress.class);
        when(addressSetting.effectiveAddress()).thenReturn(ADDRESS);
        service = new RecruitmentEmailService();
        service.visitingAddress = addressSetting;
        service.requestHeaderHolder = new RequestHeaderHolder();
    }

    // ---- A manual send: the acting recruiter is named ----------------------

    @Test
    void allFourTokens_resolve_whenAHumanIsActing() {
        actingAs(user("Hans", "Lassen", "hans.lassen@trustworks.dk"));

        try (MockedStatic<PanacheEntityBase> panache = actorRow()) {
            RecruitmentEmailRenderer.Rendered rendered =
                    service.render(template(BODY), null, null);

            assertEquals("Kom forbi " + ADDRESS + ". Venlig hilsen Hans Lassen "
                    + "(hans.lassen@trustworks.dk), Trustworks", rendered.body());
            assertTrue(rendered.unresolvedFields().isEmpty(),
                    "no token in the house vocabulary may go unresolved: "
                            + rendered.unresolvedFields());
        }
    }

    @Test
    void theRecruiterName_isTheSameTwoFieldsTheReceptionKioskSearches() {
        // firstname + lastname, via RecruitmentCalendarService.displayName —
        // the /guest iPad matches a visitor's host on exactly those, so a
        // second name-formatting rule here would send candidates to a door
        // they cannot check in at.
        actingAs(user("Hans", "Lassen", "hans@trustworks.dk"));

        try (MockedStatic<PanacheEntityBase> panache = actorRow()) {
            assertEquals("Hans Lassen", service.standardExtras().get("recruiter_name"));
        }
    }

    // ---- An automatic send: nobody acted -----------------------------------

    @Test
    void recruiterFields_areEmpty_onAnAutomaticSend() {
        // The reactor renders off any request thread, so the request-scoped
        // holder's proxy throws rather than returning null. Empty is the
        // correct answer — the same shape as the SENDER copy role resolving
        // to nobody on the same sends.
        RequestHeaderHolder offThread = mock(RequestHeaderHolder.class);
        when(offThread.getUserUuid()).thenThrow(new ContextNotActiveException());
        service.requestHeaderHolder = offThread;

        RecruitmentEmailRenderer.Rendered rendered = service.render(template(BODY), null, null);

        assertEquals("Kom forbi " + ADDRESS + ". Venlig hilsen  (), Trustworks",
                rendered.body());
        assertFalse(rendered.body().contains("{{"),
                "an automatic send must never mail a literal placeholder: " + rendered.body());
        assertTrue(rendered.unresolvedFields().isEmpty());
    }

    @Test
    void recruiterFields_areEmpty_whenTheRequestCarriedNoActor() {
        // A request context exists but no X-Requested-By reached it. Same
        // answer, and reached without a User lookup at all.
        Map<String, String> extras = service.standardExtras();

        assertEquals("", extras.get("recruiter_name"));
        assertEquals("", extras.get("recruiter_email"));
    }

    // ---- The address and the company ---------------------------------------

    @Test
    void theVisitingAddress_comesFromTheSettingTheCalendarInvitationReads() {
        assertEquals(ADDRESS, service.standardExtras().get("visiting_address"));
    }

    @Test
    void theBlankedAddressOptOut_rendersAsEmpty_notAsALiteralToken() {
        // A blank stored value is the deliberate "print no address" opt-out
        // (RecruitmentVisitingAddress returns null for it). A missing key
        // would be the one outcome that is worse than no address.
        when(addressSetting.effectiveAddress()).thenReturn(null);

        RecruitmentEmailRenderer.Rendered rendered =
                service.render(template("Adresse: {{visiting_address}}"), null, null);

        assertEquals("Adresse: ", rendered.body());
        assertTrue(rendered.unresolvedFields().isEmpty());
    }

    @Test
    void everyHouseKeyIsAlwaysPresent() {
        // The invariant behind both of the tests above: a key that is merely
        // absent is a literal placeholder in a candidate's inbox.
        Map<String, String> extras = service.standardExtras();

        for (String key : new String[]{"visiting_address", "company_name",
                "recruiter_name", "recruiter_email"}) {
            assertTrue(extras.containsKey(key), key + " must always be supplied");
            assertFalse(extras.get(key) == null, key + " must never be null");
        }
        assertEquals("Trustworks", extras.get("company_name"));
    }

    @Test
    void aCallerSuppliedExtraWins_overTheHouseValue() {
        // The renderer's own rule, kept when the two maps are merged: the
        // GDPR sweep's consent link and the calendar's own address are the
        // callers that hold a value the service cannot look up.
        RecruitmentEmailRenderer.Rendered rendered = service.render(
                template("Adresse: {{visiting_address}}"), null, null,
                Map.of("visiting_address", "Et andet sted 1"));

        assertEquals("Adresse: Et andet sted 1", rendered.body());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void actingAs(User acting) {
        RequestHeaderHolder headers = new RequestHeaderHolder();
        headers.setUserUuid(ACTOR_UUID);
        service.requestHeaderHolder = headers;
        this.actor = acting;
    }

    /** The one Panache read {@link RecruitmentEmailService#standardExtras()} does. */
    private MockedStatic<PanacheEntityBase> actorRow() {
        MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
        panache.when(() -> PanacheEntityBase.findById(ACTOR_UUID)).thenReturn(actor);
        return panache;
    }

    private static User user(String firstname, String lastname, String email) {
        User user = new User();
        user.setFirstname(firstname);
        user.setLastname(lastname);
        user.setEmail(email);
        return user;
    }

    private static RecruitmentEmailTemplate template(String body) {
        RecruitmentEmailTemplate template = new RecruitmentEmailTemplate();
        template.setSubject("Tak for din ansøgning");
        template.setBody(body);
        template.setBodyFormat(RecruitmentEmailBodyFormat.PLAIN);
        return template;
    }
}
