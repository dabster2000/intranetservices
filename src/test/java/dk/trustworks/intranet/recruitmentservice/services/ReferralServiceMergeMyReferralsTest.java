package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.MyReferralOrigin;
import dk.trustworks.intranet.recruitmentservice.dto.MyReferralRow;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentApplicationTerminal;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralDerivedStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralRelation;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free tests for {@link ReferralService#mergeMyReferrals} — the union of
 * the two referrer↔candidate sources behind "My referrals".
 * <p>
 * Deliberately in the fast tier rather than only in
 * {@code ReferralServiceIntegrationTest}: the {@code @QuarkusTest} tier is
 * not part of the CI deploy gate, and the bug this method fixes (a referrer
 * whose link exists only on the candidate row seeing an empty page) is
 * exactly the kind of regression that would otherwise rot unnoticed.
 */
class ReferralServiceMergeMyReferralsTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 1, 10, 9, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 3, 20, 9, 0);
    private static final LocalDateTime T3 = LocalDateTime.of(2026, 5, 26, 9, 0);

    // ---- The regression this whole change exists for --------------------------

    @Test
    @DisplayName("a candidate whose only link is referred_by_user_uuid still yields a row")
    void candidateRecordedLinkAppears() {
        RecruitmentCandidate sophie = candidate("cand-1", "Sophie", "Larsen", CandidateStatus.ACTIVE, T3);
        RecruitmentApplication interviewing = application("cand-1", RecruitmentStage.INTERVIEW_2, null);

        List<MyReferralRow> rows = ReferralService.mergeMyReferrals(
                List.of(), List.of(sophie), byUuid(sophie), Map.of("cand-1", List.of(interviewing)));

        assertEquals(1, rows.size());
        MyReferralRow row = rows.get(0);
        assertEquals("Sophie Larsen", row.candidateName());
        assertEquals(MyReferralOrigin.RECORDED_ON_CANDIDATE, row.origin());
        assertEquals(RecruitmentReferralDerivedStatus.INTERVIEWING, row.derivedStatus());
        assertEquals(T3, row.submittedAt(), "registration date stands in for the submission date");
        assertNull(row.referrerRelation(), "no refer form was filled in, so no declared relation");
    }

    @Test
    @DisplayName("the synthetic row id is stable across reads and is not the candidate uuid")
    void syntheticIdIsStableAndOpaque() {
        RecruitmentCandidate candidate = candidate("cand-1", "Sophie", "Larsen", CandidateStatus.ACTIVE, T3);

        String first = ReferralService.mergeMyReferrals(
                List.of(), List.of(candidate), byUuid(candidate), Map.of()).get(0).uuid();
        String second = ReferralService.mergeMyReferrals(
                List.of(), List.of(candidate), byUuid(candidate), Map.of()).get(0).uuid();

        assertEquals(first, second, "list identity must survive a refetch");
        assertNotEquals("cand-1", first, "the referrer must not get a handle to the candidate");
        assertEquals(36, first.length(), "still a uuid on the wire");
    }

    // ---- Dedupe ---------------------------------------------------------------

    @Test
    @DisplayName("a normally triaged referral — which sets BOTH sources — yields exactly one row")
    void triagedReferralIsNotDoubleCounted() {
        RecruitmentCandidate candidate = candidate("cand-1", "Sophie", "Larsen", CandidateStatus.ACTIVE, T3);
        RecruitmentReferral referral = referral("ref-1", "Sophie Larsen",
                RecruitmentReferralStatus.TRIAGED, "cand-1", T2);

        List<MyReferralRow> rows = ReferralService.mergeMyReferrals(
                List.of(referral), List.of(candidate), byUuid(candidate), Map.of());

        assertEquals(1, rows.size());
        MyReferralRow row = rows.get(0);
        assertEquals(MyReferralOrigin.REFERRAL_FORM, row.origin(), "the richer referral-row facts win");
        assertEquals(RecruitmentReferralRelation.FORMER_COLLEAGUE, row.referrerRelation());
        assertEquals(T2, row.submittedAt(), "the submission date, not the registration date");
    }

    // ---- Referral-row behaviour is unchanged ----------------------------------

    @Test
    @DisplayName("untriaged and dismissed referral rows still render, candidate or not")
    void referralRowsWithoutCandidateStillRender() {
        RecruitmentReferral awaiting = referral("ref-1", "Anna Berg",
                RecruitmentReferralStatus.SUBMITTED, null, T2);
        RecruitmentReferral dismissed = referral("ref-2", "Bo Dahl",
                RecruitmentReferralStatus.CLOSED, null, T1);

        List<MyReferralRow> rows = ReferralService.mergeMyReferrals(
                List.of(awaiting, dismissed), List.of(), Map.of(), Map.of());

        assertEquals(List.of(RecruitmentReferralDerivedStatus.AWAITING_TRIAGE,
                        RecruitmentReferralDerivedStatus.CLOSED),
                rows.stream().map(MyReferralRow::derivedStatus).toList());
        assertTrue(rows.stream().allMatch(r -> r.origin() == MyReferralOrigin.REFERRAL_FORM));
    }

    // ---- GDPR -----------------------------------------------------------------

    @Test
    @DisplayName("P19-anonymized candidates are excluded from the candidate-recorded side")
    void anonymizedCandidateIsExcluded() {
        RecruitmentCandidate erased = candidate("cand-1", "Anonymized", "Candidate",
                CandidateStatus.ANONYMIZED, T3);

        List<MyReferralRow> rows = ReferralService.mergeMyReferrals(
                List.of(), List.of(erased), byUuid(erased), Map.of());

        assertTrue(rows.isEmpty(), "erased PII must not resurface on a new surface");
    }

    // ---- Ordering & shape -----------------------------------------------------

    @Test
    @DisplayName("both sources interleave newest-first in one list")
    void unionIsSortedNewestFirst() {
        RecruitmentReferral older = referral("ref-1", "Anna Berg",
                RecruitmentReferralStatus.SUBMITTED, null, T1);
        RecruitmentReferral middle = referral("ref-2", "Bo Dahl",
                RecruitmentReferralStatus.SUBMITTED, null, T2);
        RecruitmentCandidate newest = candidate("cand-9", "Sophie", "Larsen", CandidateStatus.ACTIVE, T3);

        List<MyReferralRow> rows = ReferralService.mergeMyReferrals(
                List.of(middle, older), List.of(newest), byUuid(newest), Map.of());

        assertEquals(List.of("Sophie Larsen", "Bo Dahl", "Anna Berg"),
                rows.stream().map(MyReferralRow::candidateName).toList());
    }

    @Test
    @DisplayName("a pooled candidate reads as IN_TALENT_POOL, a terminal one as NOT_PROCEEDING")
    void candidateMilestonesDeriveFromCandidateState() {
        RecruitmentCandidate pooled = candidate("cand-1", "Emina", "Abelstedt", CandidateStatus.POOLED, T2);
        RecruitmentCandidate rejected = candidate("cand-2", "Bo", "Dahl", CandidateStatus.DECLINED, T1);
        RecruitmentApplication closed = application("cand-2", RecruitmentStage.INTERVIEW_1,
                RecruitmentApplicationTerminal.REJECTED);

        List<MyReferralRow> rows = ReferralService.mergeMyReferrals(
                List.of(), List.of(pooled, rejected),
                Map.of("cand-1", pooled, "cand-2", rejected),
                Map.of("cand-2", List.of(closed)));

        Map<String, RecruitmentReferralDerivedStatus> byName = rows.stream()
                .collect(Collectors.toMap(MyReferralRow::candidateName, MyReferralRow::derivedStatus));
        assertEquals(RecruitmentReferralDerivedStatus.IN_TALENT_POOL, byName.get("Emina Abelstedt"));
        assertEquals(RecruitmentReferralDerivedStatus.NOT_PROCEEDING, byName.get("Bo Dahl"));
    }

    @Test
    @DisplayName("no row ever carries a candidate uuid, and the DTO gains no extra fields")
    void noCandidateHandleLeaks() {
        RecruitmentCandidate candidate = candidate("cand-1", "Sophie", "Larsen", CandidateStatus.ACTIVE, T3);
        RecruitmentApplication open = application("cand-1", RecruitmentStage.OFFER, null);

        List<MyReferralRow> rows = ReferralService.mergeMyReferrals(
                List.of(), List.of(candidate), byUuid(candidate), Map.of("cand-1", List.of(open)));

        assertFalse(rows.toString().contains("cand-1"), "candidate uuid must not appear in the row");
        // The stage is only ever exposed bucketed into a milestone; the guard
        // that keeps it that way is the DTO having no stage/candidate field at
        // all, so pin the component list rather than grepping toString (the
        // milestone enum shares names like OFFER with the stage enum).
        assertEquals(List.of("uuid", "candidateName", "referrerRelation", "externalReferrerName",
                        "submittedAt", "derivedStatus", "origin"),
                java.util.Arrays.stream(MyReferralRow.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList());
        assertEquals(RecruitmentReferralDerivedStatus.OFFER, rows.get(0).derivedStatus());
    }

    @Test
    @DisplayName("a half-empty name does not produce stray whitespace")
    void displayNameToleratesMissingHalves() {
        RecruitmentCandidate onlyFirst = candidate("cand-1", "Cher", null, CandidateStatus.ACTIVE, T2);
        RecruitmentCandidate onlyLast = candidate("cand-2", "  ", "Larsen", CandidateStatus.ACTIVE, T1);

        List<MyReferralRow> rows = ReferralService.mergeMyReferrals(
                List.of(), List.of(onlyFirst, onlyLast),
                Map.of("cand-1", onlyFirst, "cand-2", onlyLast), Map.of());

        assertEquals(List.of("Cher", "Larsen"),
                rows.stream().map(MyReferralRow::candidateName).toList());
    }

    // ---- Fixtures -------------------------------------------------------------

    private static Map<String, RecruitmentCandidate> byUuid(RecruitmentCandidate candidate) {
        return Map.of(candidate.getUuid(), candidate);
    }

    private static RecruitmentCandidate candidate(String uuid, String firstName, String lastName,
                                                  CandidateStatus status, LocalDateTime createdAt) {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setUuid(uuid);
        candidate.setFirstName(firstName);
        candidate.setLastName(lastName);
        candidate.setStatus(status);
        candidate.setCreatedAt(createdAt);
        return candidate;
    }

    private static RecruitmentReferral referral(String uuid, String candidateName,
                                                RecruitmentReferralStatus status,
                                                String candidateUuid, LocalDateTime submittedAt) {
        RecruitmentReferral referral = new RecruitmentReferral();
        referral.setUuid(uuid);
        referral.setCandidateName(candidateName);
        referral.setReferrerRelation(RecruitmentReferralRelation.FORMER_COLLEAGUE);
        referral.setStatus(status);
        referral.setCandidateUuid(candidateUuid);
        referral.setSubmittedAt(submittedAt);
        return referral;
    }

    private static RecruitmentApplication application(String candidateUuid, RecruitmentStage stage,
                                                      RecruitmentApplicationTerminal terminal) {
        RecruitmentApplication application = new RecruitmentApplication();
        application.setUuid(UUID.randomUUID().toString());
        application.setCandidateUuid(candidateUuid);
        application.setStage(stage);
        application.setTerminal(terminal);
        return application;
    }
}
