package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingOutbox;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSlotApproval;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;
import dk.trustworks.intranet.recruitmentservice.slack.SlackSchedulingViews;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * SEND_RECRUITER_DM (plan §9.4): deterministic Danish status notices to
 * the request's recruiter — handbacks and interviewer-silence
 * escalations. Payload is structural ({@code notice} kind + refs); the
 * text is composed here from templates, never model prose (D6).
 */
@JBossLog
@ApplicationScoped
public class SchedulingRecruiterDmExecutor implements SchedulingOutboxExecutor {

    @Inject
    SlackService slackService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "dk.trustworks.recruitment.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    @Override
    public SchedulingOutboxAction action() {
        return SchedulingOutboxAction.SEND_RECRUITER_DM;
    }

    @Override
    public void execute(RecruitmentSchedulingOutbox row) throws Exception {
        Notice notice = QuarkusTransaction.requiringNew().call(() -> load(row));
        if (notice == null) {
            return;
        }
        slackService.sendMessage(notice.recruiter, notice.fallback, java.util.List.of(
                com.slack.api.model.block.Blocks.section(s -> s.text(
                        com.slack.api.model.block.composition.BlockCompositions
                                .markdownText(notice.text)))));
    }

    private record Notice(User recruiter, String fallback, String text) {
    }

    /** Null = stale or unresolvable notice; counts as done. */
    private Notice load(RecruitmentSchedulingOutbox row) throws Exception {
        RecruitmentSchedulingRequest request =
                RecruitmentSchedulingRequest.findById(row.getRequestUuid());
        if (request == null) {
            return null;
        }
        User recruiter = User.findById(request.getRecruiterUuid());
        if (recruiter == null || recruiter.getSlackusername() == null
                || recruiter.getSlackusername().isBlank()) {
            log.warnf("Method B recruiter notice dropped: recruiter %s has no Slack link",
                    request.getRecruiterUuid());
            return null;
        }
        JsonNode payload = objectMapper.readTree(
                row.getPayloadJson() == null ? "{}" : row.getPayloadJson());
        String kind = payload.path("notice").asText("");
        String link = baseUrl + "/recruitment/applications/" + request.getApplicationUuid();
        return switch (kind) {
            case "HANDBACK" -> {
                String reason = payload.path("reason").asText(request.getHandbackReason());
                yield new Notice(recruiter, "Interviewplanlægning givet tilbage",
                        ":inbox_tray: *Interviewplanlægningen er givet tilbage til dig*\n"
                                + "Automatikken stoppede (årsag: " + humanReason(reason) + "). "
                                + "Alle foreløbige reserveringer er frigivet.\n" + link);
            }
            case "SILENCE_ESCALATION" -> {
                String approvalUuid = payload.path("approvalUuid").asText(null);
                RecruitmentSlotApproval approval = approvalUuid == null ? null
                        : RecruitmentSlotApproval.findById(approvalUuid);
                User interviewer = approval == null ? null
                        : User.findById(approval.getUserUuid());
                RecruitmentProposedSlot slot = approval == null ? null
                        : RecruitmentProposedSlot.findById(approval.getSlotUuid());
                String who = interviewer == null ? "En interviewer"
                        : dk.trustworks.intranet.recruitmentservice.slack
                                .SlackHandlerSupport.displayName(interviewer);
                String when = slot == null ? ""
                        : " (" + SlackSchedulingViews.danishInterval(
                                slot.getSlotStart(), slot.getSlotEnd()) + ")";
                yield new Notice(recruiter, "Interviewforslag venter på svar",
                        ":hourglass: *Et interviewforslag venter stadig på svar*\n"
                                + who + " har ikke svaret på forslaget" + when
                                + " trods påmindelser.\n" + link);
            }
            default -> {
                log.warnf("Method B recruiter notice of unknown kind '%s' dropped", kind);
                yield null;
            }
        };
    }

    private static String humanReason(String reason) {
        if (reason == null) {
            return "ukendt";
        }
        return switch (reason) {
            case "AUTOMATION_DEADLINE" -> "tidsfristen for automatisk planlægning udløb";
            case "WINDOW_EXHAUSTED" -> "ingen mulige tider i det valgte vindue";
            case "RECRUITER_TAKEOVER" -> "manuel overtagelse";
            default -> reason;
        };
    }
}
