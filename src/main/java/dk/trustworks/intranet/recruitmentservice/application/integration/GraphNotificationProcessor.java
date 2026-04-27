package dk.trustworks.intranet.recruitmentservice.application.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.entities.InterviewParticipant;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ParticipantInvitationStatus;
import dk.trustworks.intranet.recruitmentservice.ports.OutlookCalendarPort;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.AttendeeResponse;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.AttendeeStatus;
import dk.trustworks.intranet.userservice.model.Employee;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Processes Microsoft Graph change-notification payloads delivered to the
 * public webhook endpoint. For every notification carrying a recognised
 * Outlook event id we:
 *
 * <ol>
 *   <li>verify {@code clientState} matches the configured shared secret —
 *       constant-time compare to defeat timing oracles;</li>
 *   <li>resolve the {@link Interview} via {@code outlookEventId};</li>
 *   <li>pull the authoritative attendee response statuses from Graph (the
 *       notification is a "something changed" signal — payloads do not carry
 *       the new state);</li>
 *   <li>map each Graph attendee to a participant by email, and patch the
 *       {@link InterviewParticipant#invitationStatus} when it has drifted.</li>
 * </ol>
 *
 * <p>Failure handling: any exception is logged and swallowed — the Graph
 * webhook contract requires HTTP 200 within ~30s, even on body that we don't
 * understand. The 24h {@code GraphReconciliationWorker} is the safety net for
 * any drop we silently accept here.
 *
 * <p>Panache static seam: {@link #findInterviewByEventId(String)},
 * {@link #findEmployeeById(String)}, {@link #findEmployeesByEmail(String)}, and
 * {@link #listParticipants(String)} are package-private so unit tests can
 * override them — Mockito cannot stub statics inherited from
 * {@code PanacheEntityBase}.
 */
@ApplicationScoped
public class GraphNotificationProcessor {

    private static final Logger LOG = Logger.getLogger(GraphNotificationProcessor.class);

    @Inject OutlookCalendarPort outlook;
    @Inject ObjectMapper mapper;

    @ConfigProperty(name = "recruitment.graph.client-state-secret")
    String clientStateSecret;

    /** Test-only seam: assemble a processor without CDI for unit tests. */
    public void initForTest(OutlookCalendarPort outlook, ObjectMapper mapper, String clientStateSecret) {
        this.outlook = outlook;
        this.mapper = mapper;
        this.clientStateSecret = clientStateSecret;
    }

    @Transactional
    public void process(String body) {
        try {
            processInternal(body);
        } catch (Exception ex) {
            LOG.warnf(ex, "Graph notification processing failed; body dropped: %s", ex.getMessage());
        }
    }

    private void processInternal(String body) throws Exception {
        if (body == null || body.isBlank()) {
            LOG.debug("Graph notification: empty body, skipping");
            return;
        }
        JsonNode root = mapper.readTree(body);
        JsonNode value = root.get("value");
        if (value == null || !value.isArray()) {
            LOG.debug("Graph notification: no 'value' array, skipping");
            return;
        }

        for (JsonNode n : value) {
            String clientState = textOrNull(n, "clientState");
            if (!constantTimeEquals(clientState, clientStateSecret)) {
                LOG.warn("Graph notification: clientState mismatch, dropping notification");
                continue;
            }
            String eventId = extractEventId(n);
            if (eventId == null) continue;

            Interview iv = findInterviewByEventId(eventId);
            if (iv == null) {
                LOG.debugf("Graph notification: no interview for outlookEventId=%s", eventId);
                continue;
            }
            patchParticipants(iv);
        }
    }

    private void patchParticipants(Interview iv) {
        Employee organizer = findEmployeeById(iv.createdBy);
        if (organizer == null || organizer.getEmail() == null) {
            LOG.debugf("Graph notification: skipping iv=%s — organizer (createdBy=%s) missing or has no email",
                    iv.uuid, iv.createdBy);
            return;
        }
        try {
            List<AttendeeResponse> responses =
                    outlook.getAttendeeStatuses(organizer.getEmail(), iv.outlookEventId);
            List<InterviewParticipant> ps = listParticipants(iv.uuid);
            for (AttendeeResponse r : responses) {
                ParticipantInvitationStatus mapped = mapStatus(r.status());
                List<Employee> emps = findEmployeesByEmail(r.email());
                if (emps.isEmpty()) continue;
                String uuid = emps.get(0).getUuid();
                for (InterviewParticipant p : ps) {
                    if (uuid.equals(p.userUuid) && p.invitationStatus != mapped) {
                        LOG.debugf("Graph notification: patching participant %s on iv=%s: %s → %s",
                                p.uuid, iv.uuid, p.invitationStatus, mapped);
                        p.invitationStatus = mapped;
                    }
                }
            }
        } catch (Exception ex) {
            LOG.warnf(ex, "Graph notification: error patching iv=%s: %s", iv.uuid, ex.getMessage());
        }
    }

    private static String extractEventId(JsonNode notification) {
        JsonNode resourceData = notification.get("resourceData");
        if (resourceData == null) return null;
        return textOrNull(resourceData, "id");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    static ParticipantInvitationStatus mapStatus(AttendeeStatus s) {
        return switch (s) {
            case ACCEPTED -> ParticipantInvitationStatus.ACCEPTED;
            case DECLINED -> ParticipantInvitationStatus.DECLINED;
            case TENTATIVE -> ParticipantInvitationStatus.TENTATIVE;
            case NONE -> ParticipantInvitationStatus.INVITED;
        };
    }

    /**
     * Constant-time equality on UTF-8 byte arrays. Returns false when either
     * argument is null. Length is leaked (unavoidable without padding) but the
     * content compare is timing-safe.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ab, bb);
    }

    /* Package-private + protected (where needed for cross-package test subclasses)
     * Panache static seams. Mockito cannot stub statics inherited from PanacheEntityBase,
     * so unit tests subclass the processor and override these methods. */
    protected Interview findInterviewByEventId(String eventId) {
        return Interview.<Interview>find("outlookEventId = ?1", eventId).firstResult();
    }

    protected Employee findEmployeeById(String uuid) {
        return Employee.findById(uuid);
    }

    protected List<Employee> findEmployeesByEmail(String email) {
        return Employee.list("email = ?1", email);
    }

    protected List<InterviewParticipant> listParticipants(String interviewUuid) {
        return InterviewParticipant.list("interviewUuid = ?1", interviewUuid);
    }
}
