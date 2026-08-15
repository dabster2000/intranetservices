package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manual-delivery draft (owner request 2026-08-15): the recruiter
 * copies this text to the candidate by hand, so the wording is a
 * product surface — deterministic (D6), Danish, numbered options, the
 * link, the deadline, the signature. Pinned DB-free.
 */
class SchedulingManualDraftTest {

    @Test
    void draft_carriesGreetingOptionsLinkDeadlineAndSignature() {
        RecruitmentSchedulingRequest request = new RecruitmentSchedulingRequest();
        request.setKind(RecruitmentInterviewKind.ROUND);
        request.setRound(1);
        request.setDurationMinutes(45);
        request.setOnlineMeeting(true);

        RecruitmentProposedSlot first = new RecruitmentProposedSlot();
        first.setSlotStart(LocalDateTime.of(2026, 8, 18, 10, 0));
        first.setSlotEnd(LocalDateTime.of(2026, 8, 18, 10, 45));
        RecruitmentProposedSlot second = new RecruitmentProposedSlot();
        second.setSlotStart(LocalDateTime.of(2026, 8, 19, 15, 0));
        second.setSlotEnd(LocalDateTime.of(2026, 8, 19, 15, 45));

        String draft = SchedulingRecruiterDmExecutor.manualDraft(
                "Jane", request, List.of(first, second),
                "https://intra.trustworks.dk/interview-valg/tok123",
                LocalDateTime.of(2026, 8, 20, 16, 0), "Hans Ernst Lassen");

        assertTrue(draft.startsWith("Hej Jane,"), draft);
        assertTrue(draft.contains("interview 1 (45 min, online via Microsoft Teams)"), draft);
        assertTrue(draft.contains("1. tirsdag den 18. august kl. 10.00–10.45"), draft);
        assertTrue(draft.contains("2. onsdag den 19. august kl. 15.00–15.45"), draft);
        assertTrue(draft.contains("https://intra.trustworks.dk/interview-valg/tok123"), draft);
        assertTrue(draft.contains("senest torsdag den 20. august kl. 16.00"), draft);
        assertTrue(draft.endsWith("Venlig hilsen\nHans Ernst Lassen"), draft);
        assertFalse(draft.contains("null"), draft);
    }

    @Test
    void draft_withoutDeadline_omitsTheDeadlineLine() {
        RecruitmentSchedulingRequest request = new RecruitmentSchedulingRequest();
        request.setKind(RecruitmentInterviewKind.INFORMAL);
        request.setDurationMinutes(30);
        String draft = SchedulingRecruiterDmExecutor.manualDraft(
                "kandidat", request, List.of(),
                "https://x/interview-valg/t", null, "Hans");
        assertTrue(draft.contains("uformel snak (30 min)"), draft);
        assertFalse(draft.contains("senest"), draft);
    }
}
