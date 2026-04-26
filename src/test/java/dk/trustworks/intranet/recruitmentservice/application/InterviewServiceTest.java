package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewRoundType;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ParticipantRole;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure-JUnit tests covering the early-exit validation gates in
 * {@link InterviewService#schedule} that fire before any persistence
 * collaborator is touched. Full integration / DB-backed cases live in the
 * {@code @QuarkusTest} suite that ships with the Phase G end-to-end pass.
 */
class InterviewServiceTest {

    private final InterviewService svc = new InterviewService();

    @Test
    void schedule_throws_ifPastScheduledAt() {
        ScheduleInterviewCommand cmd = new ScheduleInterviewCommand(
                "app-1", InterviewRoundType.FIRST, LocalDateTime.now().minusDays(1), 60,
                List.of(new ScheduleInterviewCommand.Participant(
                        "u-1", ParticipantRole.LEAD_INTERVIEWER, true)),
                null);
        assertThrows(IllegalArgumentException.class, () -> svc.schedule(cmd, "actor-1"));
    }

    @Test
    void schedule_throws_ifNullScheduledAt() {
        ScheduleInterviewCommand cmd = new ScheduleInterviewCommand(
                "app-1", InterviewRoundType.FIRST, null, 60,
                List.of(new ScheduleInterviewCommand.Participant(
                        "u-1", ParticipantRole.LEAD_INTERVIEWER, true)),
                null);
        assertThrows(IllegalArgumentException.class, () -> svc.schedule(cmd, "actor-1"));
    }

    @Test
    void schedule_throws_ifNullRoundType() {
        ScheduleInterviewCommand cmd = new ScheduleInterviewCommand(
                "app-1", null, LocalDateTime.now().plusDays(2), 60,
                List.of(new ScheduleInterviewCommand.Participant(
                        "u-1", ParticipantRole.LEAD_INTERVIEWER, true)),
                null);
        assertThrows(IllegalArgumentException.class, () -> svc.schedule(cmd, "actor-1"));
    }

    @Test
    void schedule_throws_ifBlankApplicationUuid() {
        ScheduleInterviewCommand cmd = new ScheduleInterviewCommand(
                "  ", InterviewRoundType.FIRST, LocalDateTime.now().plusDays(2), 60,
                List.of(new ScheduleInterviewCommand.Participant(
                        "u-1", ParticipantRole.LEAD_INTERVIEWER, true)),
                null);
        assertThrows(IllegalArgumentException.class, () -> svc.schedule(cmd, "actor-1"));
    }
}
