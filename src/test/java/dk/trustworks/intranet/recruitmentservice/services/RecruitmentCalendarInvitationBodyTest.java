package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interview invitation's body text. The candidate is a required
 * attendee whenever they have an email, so this body leaves the company —
 * it used to be the internal note "Scheduled via the Trustworks intranet —
 * see /recruitment/interviews for the interview kit", which pointed
 * candidates at a path only employees can open.
 * <p>
 * Plain unit test — no Quarkus boot: the body is pure text derived from the
 * interview, candidate and position, so it belongs in the DB-free tier that
 * gates deploys (the {@code @QuarkusTest} tier is ungated and rots
 * silently).
 */
class RecruitmentCalendarInvitationBodyTest {

    private static final String INTERNAL_NOTE =
            "Scheduled via the Trustworks intranet — see /recruitment/interviews for the interview kit.";

    @Test
    void candidateInvited_roundInterview_addressesTheCandidate_withNoInternalLink() {
        String body = RecruitmentCalendarService.invitationBody(
                interview(RecruitmentInterviewKind.ROUND), candidate("Anna", "Nielsen"), true);

        assertEquals("""
                Kære Anna

                Vi glæder os til at møde dig til samtale hos Trustworks.

                Er du forhindret, eller har du spørgsmål inden vi ses, er du velkommen til at svare på denne invitation.

                Med venlig hilsen
                Trustworks""", body);
    }

    @Test
    void candidateInvited_informalChat_saysUformelSnak() {
        String body = RecruitmentCalendarService.invitationBody(
                interview(RecruitmentInterviewKind.INFORMAL), candidate("Anna", "Nielsen"), true);

        assertTrue(body.contains("Vi glæder os til en uformel snak med dig hos Trustworks."),
                "informal chats must not be announced as a samtale: " + body);
    }

    /**
     * The position is named in the subject line, not in the body — the body
     * greets and sets expectations, nothing more.
     */
    @Test
    void candidateInvited_neverNamesThePosition() {
        for (RecruitmentInterviewKind kind : RecruitmentInterviewKind.values()) {
            String body = RecruitmentCalendarService.invitationBody(
                    interview(kind), candidate("Anna", "Nielsen"), true);
            assertFalse(body.contains("stilling"), "position mentioned for " + kind + ": " + body);
        }
    }

    /**
     * The regression guard proper: nothing internal may reach a body the
     * candidate receives — not the old note, not a bare intranet path.
     */
    @Test
    void candidateInvited_neverCarriesInternalPointers() {
        for (RecruitmentInterviewKind kind : RecruitmentInterviewKind.values()) {
            String body = RecruitmentCalendarService.invitationBody(
                    interview(kind), candidate("Anna", "Nielsen"), true);
            assertFalse(body.contains(INTERNAL_NOTE), "old internal note leaked for " + kind);
            assertFalse(body.contains("/recruitment"), "internal path leaked for " + kind + ": " + body);
            assertFalse(body.contains("intranet"), "intranet mentioned for " + kind + ": " + body);
        }
    }

    @Test
    void candidateWithoutFirstName_greetsNamelessly_neverBySurname() {
        String body = RecruitmentCalendarService.invitationBody(
                interview(RecruitmentInterviewKind.ROUND), candidate(null, "Nielsen"), true);

        assertTrue(body.startsWith("Hej\n"), "expected a nameless greeting, got: " + body);
        assertFalse(body.contains("Kære"), "must not address a candidate by surname: " + body);
    }

    @Test
    void blankFirstName_treatedAsMissing() {
        String body = RecruitmentCalendarService.invitationBody(
                interview(RecruitmentInterviewKind.ROUND), candidate("   ", "Nielsen"), true);

        assertTrue(body.startsWith("Hej\n"), "expected a nameless greeting, got: " + body);
    }

    /**
     * No candidate email ⇒ the candidate is not on the invitation at all
     * and the event is interviewers-only, so the internal pointer stays: it
     * is useful there and nobody external can see it.
     */
    @Test
    void candidateNotInvited_keepsTheInternalNote() {
        String body = RecruitmentCalendarService.invitationBody(
                interview(RecruitmentInterviewKind.ROUND), candidate("Anna", "Nielsen"), false);

        assertEquals(INTERNAL_NOTE, body);
    }

    // ---- Attendee display names --------------------------------------------

    /**
     * Interviewers used to be invited address-only. The candidate's mail
     * client has no line to our directory, so those attendees rendered as
     * raw {@code firstname.lastname@trustworks.dk} — the invitation's last
     * internal-looking corner.
     */
    @Test
    void interviewerDisplayName_isTheFullName() {
        assertEquals("Ida Interviewer", RecruitmentCalendarService.displayName(user("Ida", "Interviewer")));
    }

    @Test
    void interviewerDisplayName_halfFilledRow_fallsBackToTheAddress() {
        // null, not "Ida null": a broken name is worse than none, because
        // Outlook shows the address when the name is absent.
        assertEquals("Ida", RecruitmentCalendarService.displayName(user("Ida", null)));
        assertEquals("Interviewer", RecruitmentCalendarService.displayName(user(null, "Interviewer")));
        assertNull(RecruitmentCalendarService.displayName(user(null, null)));
        assertNull(RecruitmentCalendarService.displayName(user("  ", " ")));
        assertNull(RecruitmentCalendarService.displayName(null));
    }

    // ---- Fixtures ----------------------------------------------------------

    private static User user(String firstname, String lastname) {
        User user = new User();
        user.setFirstname(firstname);
        user.setLastname(lastname);
        return user;
    }

    private static RecruitmentInterview interview(RecruitmentInterviewKind kind) {
        RecruitmentInterview interview = new RecruitmentInterview();
        interview.setKind(kind);
        if (kind == RecruitmentInterviewKind.ROUND) {
            interview.setRound(1);
        }
        return interview;
    }

    private static RecruitmentCandidate candidate(String firstName, String lastName) {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setFirstName(firstName);
        candidate.setLastName(lastName);
        return candidate;
    }
}
