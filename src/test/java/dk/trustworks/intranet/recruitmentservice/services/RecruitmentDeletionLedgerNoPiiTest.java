package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deletion ledger must not carry the deleted candidate's name
 * (verification finding, 2026-08-19).
 *
 * <p>{@code recruitment_candidate_deletions} is the only trace that a
 * candidate ever existed and that someone removed them. It has no foreign
 * key, it is on the prod → staging sync exclusion list, and nothing ever
 * cleans it — so anything written into it is permanent. Its own migration
 * header states the rule: no name, no email, no phone, no LinkedIn, nothing
 * beyond the now-meaningless uuid, "storing the name here would defeat the
 * deletion it records".</p>
 *
 * <p>The value that broke the rule was {@code sharepoint_folder_path}. It
 * went into {@code residue} verbatim — the same column
 * {@code RecruitmentCandidate.anonymize} nulls with the comment "contains
 * the candidate's name". These tests pin the redaction and, with a source
 * scan, pin that the raw value cannot come back through a future edit.</p>
 */
class RecruitmentDeletionLedgerNoPiiTest {

    private static final String CANDIDATE = "11111111-2222-3333-4444-555555555555";
    private static final String FIRST = "Ingeborg";
    private static final String LAST = "Falkenberg-Mikkelsen";
    private static final String PATH =
            "/sites/HR/Shared Documents/Recruitment/2026/" + FIRST + " " + LAST;

    private static final Path SERVICE_SOURCE = Path.of(
            "src/main/java/dk/trustworks/intranet/recruitmentservice/services/"
                    + "RecruitmentCandidateHardDeleteService.java");

    // ---- The redaction itself ---------------------------------------------

    @Test
    void theHandleCarriesNeitherTheNameNorThePath() {
        String rendered = RecruitmentCandidateHardDeleteService
                .redactedFolderHandle(PATH, CANDIDATE).toString();

        assertFalse(rendered.contains(FIRST), "the first name must not survive into the ledger");
        assertFalse(rendered.contains(LAST), "the surname must not survive into the ledger");
        assertFalse(rendered.contains(PATH), "the raw path must not survive into the ledger");
        assertFalse(rendered.contains("Shared Documents"),
                "no path segment at all — a parent segment can name a person just as well "
                        + "as the leaf does");
    }

    @Test
    void theDigestIsSha256OverTheCandidateUuidAndThePath() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(CANDIDATE.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        md.update(PATH.getBytes(StandardCharsets.UTF_8));
        String expected = HexFormat.of().formatHex(md.digest());

        Map<String, Object> handle =
                RecruitmentCandidateHardDeleteService.redactedFolderHandle(PATH, CANDIDATE);

        assertEquals(expected, handle.get("pathSha256"),
                "an operator holding a folder path must be able to prove it is this row's by "
                        + "recomputing the documented digest — if this changes, the note in "
                        + "the handle and the V515 header are both now wrong");
        assertEquals(64, ((String) handle.get("pathSha256")).length());
    }

    @Test
    void theDigestIsSaltedSoOneTableCannotCoverEveryRow() {
        String a = (String) RecruitmentCandidateHardDeleteService
                .redactedFolderHandle(PATH, CANDIDATE).get("pathSha256");
        String b = (String) RecruitmentCandidateHardDeleteService
                .redactedFolderHandle(PATH, "99999999-8888-7777-6666-555555555555").get("pathSha256");

        assertNotEquals(a, b,
                "the same path under two candidates must not hash alike — the candidate uuid "
                        + "is the salt, and it is what stops one precomputed table of plausible "
                        + "names unmasking the whole ledger at once");
    }

    @Test
    void theHandleStillTellsAnOperatorWhatToDo() {
        Map<String, Object> handle =
                RecruitmentCandidateHardDeleteService.redactedFolderHandle(PATH, CANDIDATE);

        assertEquals(PATH.length(), handle.get("pathLength"));
        String note = String.valueOf(handle.get("note"));
        assertTrue(note.contains("sha256"),
                "withholding the path is only defensible if the row says how to match it");
        assertTrue(note.toLowerCase().contains("sharepoint"));
    }

    // ---- The ratchet ------------------------------------------------------

    @Test
    void theServiceNeverPutsTheRawFolderPathIntoResidue() throws Exception {
        String source = Files.readString(SERVICE_SOURCE);

        assertTrue(source.contains("residue.put(\"sharepointFolderRetained\","),
                "the residue key moved — re-point this ratchet before deleting it");
        assertFalse(source.contains("residue.put(\"sharepointFolderRetained\", sharepointFolderPath)"),
                "the SharePoint folder path embeds the candidate's name (see "
                        + "RecruitmentCandidate.anonymize) and the ledger is permanent — it "
                        + "must go through redactedFolderHandle, never straight in");

        int put = source.indexOf("residue.put(\"sharepointFolderRetained\",");
        String call = source.substring(put, Math.min(source.length(), put + 200));
        assertTrue(call.contains("redactedFolderHandle("),
                "the only legal value for this key is the redacted handle, found instead: " + call);
    }

    @Test
    void everyOtherResiduePutCarriesAnIdentifierOrACount() throws Exception {
        String source = Files.readString(SERVICE_SOURCE);
        // The candidate's own fields are the ones that must never reach the
        // ledger. getFullname()/getFirstName()/getLastName()/getEmail() have no
        // business anywhere in this service: the confirm-text comparison lives
        // in the resource, not here.
        for (String forbidden : new String[]{
                "getFullname()", "getFirstName()", "getLastName()", "getEmail()",
                "getPhone()", "getLinkedinUrl()"}) {
            assertFalse(source.contains(forbidden),
                    "the hard-delete service must not read " + forbidden + " at all — every "
                            + "value it produces ends up in a permanent, never-cleaned ledger row");
        }
    }
}
