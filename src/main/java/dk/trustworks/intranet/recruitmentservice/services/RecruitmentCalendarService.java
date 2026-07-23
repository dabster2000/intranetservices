package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.sharepoint.client.GraphApiClient;
import dk.trustworks.intranet.sharepoint.client.GraphApiClient.CalendarEventRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outlook calendar bridging for interview scheduling (ATS plan §P11).
 * Ships DARK behind {@code dk.trustworks.recruitment.graph.calendar.enabled}
 * (default {@code false}) — the Graph {@code Calendars.ReadWrite}
 * application permission is a tenant-admin workstream with lead time (plan
 * §4); until IT grants it, scheduling is manual and this service is a
 * silent no-op. The interview model ({@code graph_event_id} nullable)
 * supports both modes, so flipping the property later needs no migration.
 * <p>
 * Failure posture: the intranet interview row is the source of truth — a
 * Graph failure is logged and swallowed, scheduling itself NEVER fails on
 * calendar trouble. {@code graph_event_id} simply stays null (create) or
 * keeps its last value (update/cancel).
 * <p>
 * Event shape: created in the FIRST interviewer's calendar (they act as
 * organizer); remaining interviewers and the candidate (external, when an
 * email exists) are invited as required attendees. Duration is fixed at
 * 60 minutes.
 */
// ponytail: fixed 60-minute duration — add a duration column + picker when
// Graph scheduling actually goes live and real bookings need other lengths.
@JBossLog
@ApplicationScoped
public class RecruitmentCalendarService {

    static final int DURATION_MINUTES = 60;

    /**
     * Lazily resolved: the Graph REST client's OIDC filter needs the
     * {@code graph} client credentials, which environments without the
     * toggle (tests, local) may not configure — with the toggle off the
     * client must never be instantiated.
     */
    @Inject
    @RestClient
    Instance<GraphApiClient> graphApiClientInstance;

    /** Test seam; resolved from {@link #graphApiClientInstance} on first use. */
    GraphApiClient graphApiClient;

    @ConfigProperty(name = "dk.trustworks.recruitment.graph.calendar.enabled", defaultValue = "false")
    boolean calendarEnabled;

    private GraphApiClient graph() {
        if (graphApiClient == null) {
            graphApiClient = graphApiClientInstance.get();
        }
        return graphApiClient;
    }

    public boolean isEnabled() {
        return calendarEnabled;
    }

    /**
     * Create the Outlook event for a newly scheduled interview.
     *
     * @return the Graph event id to store on the interview, or empty when
     *         the toggle is off, no organizer mailbox resolves, or Graph
     *         fails (logged, never thrown)
     */
    public Optional<String> createEvent(RecruitmentInterview interview,
                                        RecruitmentCandidate candidate,
                                        RecruitmentPosition position) {
        if (!calendarEnabled) {
            return Optional.empty();
        }
        try {
            String organizer = organizerMailbox(interview);
            if (organizer == null) {
                log.warnv("Graph calendar: no organizer mailbox resolvable for interview {0} — skipping",
                        interview.getUuid());
                return Optional.empty();
            }
            GraphApiClient.CalendarEvent created = graph().createCalendarEvent(
                    organizer, buildEvent(interview, candidate, position));
            return Optional.ofNullable(created != null ? created.id() : null);
        } catch (Exception e) {
            log.warnv("Graph calendar create failed for interview {0}: {1} — proceeding without calendar event",
                    interview.getUuid(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Push a reschedule (new time/location/attendees) to the existing Outlook event. */
    public void updateEvent(RecruitmentInterview interview,
                            RecruitmentCandidate candidate,
                            RecruitmentPosition position) {
        if (!calendarEnabled || interview.getGraphEventId() == null) {
            return;
        }
        try {
            String organizer = organizerMailbox(interview);
            if (organizer == null) {
                return;
            }
            graph().updateCalendarEvent(organizer, interview.getGraphEventId(),
                    buildEvent(interview, candidate, position));
        } catch (Exception e) {
            log.warnv("Graph calendar update failed for interview {0}: {1} — calendar may be stale",
                    interview.getUuid(), e.getMessage());
        }
    }

    /** Cancel the Outlook event (attendees get a cancellation). 404 = already gone, fine. */
    public void cancelEvent(RecruitmentInterview interview) {
        if (!calendarEnabled || interview.getGraphEventId() == null) {
            return;
        }
        try {
            String organizer = organizerMailbox(interview);
            if (organizer == null) {
                return;
            }
            graph().deleteCalendarEvent(organizer, interview.getGraphEventId());
        } catch (WebApplicationException e) {
            if (e.getResponse() == null || e.getResponse().getStatus() != 404) {
                log.warnv("Graph calendar delete failed for interview {0}: {1}",
                        interview.getUuid(), e.getMessage());
            }
        } catch (Exception e) {
            log.warnv("Graph calendar delete failed for interview {0}: {1}",
                    interview.getUuid(), e.getMessage());
        }
    }

    // ---- Shaping -----------------------------------------------------------

    private CalendarEventRequest buildEvent(RecruitmentInterview interview,
                                            RecruitmentCandidate candidate,
                                            RecruitmentPosition position) {
        Objects.requireNonNull(interview.getScheduledAt(), "scheduledAt must be set before calendar sync");
        String subject = interview.getKind() == RecruitmentInterviewKind.INFORMAL
                ? "Uformel snak: %s — %s".formatted(candidateName(candidate), position.getTitle())
                : "Interview %d: %s — %s".formatted(interview.getRound(),
                        candidateName(candidate), position.getTitle());

        List<CalendarEventRequest.Attendee> attendees = new ArrayList<>();
        List<String> interviewers = interview.getInterviewerUuids();
        for (int i = 1; i < interviewers.size(); i++) {
            String email = userEmail(interviewers.get(i));
            if (email != null) {
                attendees.add(required(email, null));
            }
        }
        if (candidate.getEmail() != null && !candidate.getEmail().isBlank()) {
            attendees.add(required(candidate.getEmail(), candidateName(candidate)));
        }

        return new CalendarEventRequest(
                subject,
                new CalendarEventRequest.ItemBody("text",
                        "Scheduled via the Trustworks intranet — see /recruitment/interviews for the interview kit."),
                new CalendarEventRequest.DateTimeTimeZone(interview.getScheduledAt().toString(), "UTC"),
                new CalendarEventRequest.DateTimeTimeZone(
                        interview.getScheduledAt().plusMinutes(DURATION_MINUTES).toString(), "UTC"),
                interview.getLocation() != null
                        ? new CalendarEventRequest.EventLocation(interview.getLocation())
                        : null,
                attendees);
    }

    private static CalendarEventRequest.Attendee required(String email, String name) {
        return new CalendarEventRequest.Attendee(
                new CalendarEventRequest.Attendee.EmailAddress(email, name), "required");
    }

    /** The first interviewer's mailbox — they act as organizer. */
    private String organizerMailbox(RecruitmentInterview interview) {
        List<String> interviewers = interview.getInterviewerUuids();
        if (interviewers == null || interviewers.isEmpty()) {
            return null;
        }
        return userEmail(interviewers.get(0));
    }

    private String userEmail(String userUuid) {
        User user = User.findById(userUuid);
        return user != null && user.getEmail() != null && !user.getEmail().isBlank()
                ? user.getEmail()
                : null;
    }

    private static String candidateName(RecruitmentCandidate candidate) {
        String first = candidate.getFirstName() == null ? "" : candidate.getFirstName();
        String last = candidate.getLastName() == null ? "" : candidate.getLastName();
        return (first + " " + last).trim();
    }
}
