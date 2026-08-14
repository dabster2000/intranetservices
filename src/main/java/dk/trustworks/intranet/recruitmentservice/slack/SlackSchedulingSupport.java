package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSlotApproval;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

/**
 * Shared resolution for the Method B Slack handlers (plan §9.2): a
 * button's {@code value} / a modal's {@code private_metadata} carries an
 * approval uuid — a CLAIM. This resolves it to the full aggregate and
 * re-authorizes: the approval must belong to the acting interviewer.
 * Anything off answers the standard "no longer active" ephemeral and
 * causes ZERO side effects.
 */
@JBossLog
@ApplicationScoped
public class SlackSchedulingSupport {

    static final String GONE_TEXT =
            "Dette interviewforslag er ikke længere aktivt.";

    /** The resolved aggregate; {@code deny} != null ⇒ stop with it. */
    public record Resolved(
            SlackInboundResponse deny,
            RecruitmentSlotApproval approval,
            RecruitmentProposedSlot slot,
            RecruitmentSchedulingRequest request,
            RecruitmentApplication application,
            String candidateName,
            String positionTitle) {

        static Resolved denied(SlackInboundResponse deny) {
            return new Resolved(deny, null, null, null, null, null, null);
        }
    }

    /** Resolve + re-authorize one approval claim for {@code actor}. */
    public Resolved resolve(User actor, String approvalUuid) {
        if (approvalUuid == null || approvalUuid.isBlank()) {
            return Resolved.denied(SlackInboundResponse.handled(GONE_TEXT));
        }
        RecruitmentSlotApproval approval = RecruitmentSlotApproval.findById(approvalUuid);
        if (approval == null || !approval.getUserUuid().equals(actor.getUuid())) {
            // Not found and not-yours are indistinguishable on purpose.
            return Resolved.denied(SlackInboundResponse.handled(GONE_TEXT));
        }
        RecruitmentProposedSlot slot = RecruitmentProposedSlot.findById(approval.getSlotUuid());
        RecruitmentSchedulingRequest request = slot == null ? null
                : RecruitmentSchedulingRequest.findById(slot.getRequestUuid());
        if (slot == null || request == null) {
            return Resolved.denied(SlackInboundResponse.handled(GONE_TEXT));
        }
        RecruitmentApplication application =
                RecruitmentApplication.findById(request.getApplicationUuid());
        RecruitmentCandidate candidate = application == null ? null
                : RecruitmentCandidate.findById(application.getCandidateUuid());
        RecruitmentPosition position = application == null ? null
                : RecruitmentPosition.findById(application.getPositionUuid());
        return new Resolved(null, approval, slot, request, application,
                candidateName(candidate),
                position != null ? position.getTitle() : null);
    }

    static String candidateName(RecruitmentCandidate candidate) {
        if (candidate == null) {
            return "kandidaten";
        }
        String first = candidate.getFirstName() == null ? "" : candidate.getFirstName();
        String last = candidate.getLastName() == null ? "" : candidate.getLastName();
        String name = (first + " " + last).trim();
        return name.isEmpty() ? "kandidaten" : name;
    }
}
