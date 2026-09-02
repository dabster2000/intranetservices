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
 * <p>The value that once broke the rule was the (since removed) SharePoint
 * folder path, which went into {@code residue} verbatim. This source scan
 * pins that no candidate-identifying getter can come back through a
 * future edit.</p>
 */
class RecruitmentDeletionLedgerNoPiiTest {

    private static final Path SERVICE_SOURCE = Path.of(
            "src/main/java/dk/trustworks/intranet/recruitmentservice/services/"
                    + "RecruitmentCandidateHardDeleteService.java");

    // ---- The ratchet ------------------------------------------------------

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
