package dk.trustworks.intranet.recruitmentservice.slack;

import com.slack.api.model.block.LayoutBlock;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSlotApproval;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The combined proposal message's single source of truth: every Slack
 * surface that changes an option's state — button handlers, the F12
 * message resolution, finalization — re-renders the affected messages
 * from PERSISTED state through here, instead of hand-writing per-slot
 * rewrites. A message is identified by the (channel, ts) ref stored on
 * its approval rows; the approvals sharing one ref ARE that message's
 * option list, which also keeps messages sent before the combined-card
 * change working — they simply re-render as one-option cards.
 */
@JBossLog
@ApplicationScoped
public class SlackProposalCardService {

    @Inject
    SlackService slackService;

    /** One rendered rewrite of one proposal message. */
    public record ComputedUpdate(String channelId, String ts, String fallback,
                                 List<LayoutBlock> blocks) {
    }

    /**
     * Compute rewrites for EVERY proposal message of the request — all
     * interviewers, all rounds. Call inside a transaction; perform the
     * result whenever appropriate (handlers do it inline, the F12 path
     * after its commit).
     */
    public List<ComputedUpdate> computeRequestRefresh(RecruitmentSchedulingRequest request) {
        Map<TargetKey, List<OptionRow>> groups = new LinkedHashMap<>();
        List<RecruitmentProposedSlot> slots = RecruitmentProposedSlot
                .list("requestUuid = ?1", request.getUuid());
        for (RecruitmentProposedSlot slot : slots) {
            List<RecruitmentSlotApproval> approvals =
                    RecruitmentSlotApproval.list("slotUuid = ?1", slot.getUuid());
            for (RecruitmentSlotApproval approval : approvals) {
                if (approval.getSlackChannelId() == null
                        || approval.getSlackMessageTs() == null) {
                    continue;
                }
                groups.computeIfAbsent(new TargetKey(approval.getSlackChannelId(),
                                approval.getSlackMessageTs()), key -> new ArrayList<>())
                        .add(new OptionRow(slot, approval));
            }
        }
        Names names = namesOf(request);
        List<ComputedUpdate> updates = new ArrayList<>(groups.size());
        groups.forEach((target, rows) ->
                updates.add(render(request, target, rows, names)));
        return updates;
    }

    /**
     * The click-target guarantee: the message the interviewer clicked is
     * rewritten even when it is not a stored ref anymore (an older card
     * whose ref was overwritten by a reminder). It gets the actor's
     * current stored-message render — state-true, whatever subset of
     * options the old message once showed.
     */
    public List<ComputedUpdate> computeRefreshAfterAction(
            RecruitmentSchedulingRequest request,
            RecruitmentSlotApproval actorApproval,
            String clickedChannelId, String clickedTs) {
        List<ComputedUpdate> updates = computeRequestRefresh(request);
        if (clickedChannelId == null || clickedTs == null) {
            return updates;
        }
        boolean clickedIsStored = updates.stream().anyMatch(update ->
                update.channelId().equals(clickedChannelId)
                        && update.ts().equals(clickedTs));
        if (clickedIsStored) {
            return updates;
        }
        ComputedUpdate source = updates.stream()
                .filter(update -> update.channelId()
                        .equals(actorApproval.getSlackChannelId())
                        && update.ts().equals(actorApproval.getSlackMessageTs()))
                .findFirst()
                .orElse(null);
        if (source == null) {
            // No stored ref for the actor at all — render the clicked
            // message as a one-option card of the acted-on approval.
            RecruitmentProposedSlot slot =
                    RecruitmentProposedSlot.findById(actorApproval.getSlotUuid());
            if (slot == null) {
                return updates;
            }
            source = render(request, new TargetKey(clickedChannelId, clickedTs),
                    List.of(new OptionRow(slot, actorApproval)), namesOf(request));
            updates = new ArrayList<>(updates);
            updates.add(source);
            return updates;
        }
        updates = new ArrayList<>(updates);
        updates.add(new ComputedUpdate(clickedChannelId, clickedTs,
                source.fallback(), source.blocks()));
        return updates;
    }

    /** chat.update every computed rewrite; one dead message (deleted DM,
     * deactivated user) never blocks the rest — cards are cosmetic. */
    public void perform(List<ComputedUpdate> updates) {
        for (ComputedUpdate update : updates) {
            try {
                slackService.updateMessage(update.channelId(), update.ts(),
                        update.fallback(), update.blocks());
            } catch (Exception e) {
                log.warnf("Method B proposal-card refresh failed (channel=%s): %s",
                        update.channelId(), e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------

    private record TargetKey(String channelId, String ts) {
    }

    private record OptionRow(RecruitmentProposedSlot slot,
                             RecruitmentSlotApproval approval) {
    }

    public record Names(String candidateName, String positionTitle) {
    }

    private ComputedUpdate render(RecruitmentSchedulingRequest request,
                                  TargetKey target, List<OptionRow> rows,
                                  Names names) {
        List<SlackSchedulingViews.OptionView> options = rows.stream()
                .sorted(Comparator.comparingInt(row -> row.slot().getOptionNo()))
                .map(row -> new SlackSchedulingViews.OptionView(row.slot(),
                        row.approval().getUuid(),
                        SlackSchedulingViews.optionState(row.slot().getStatus(),
                                row.approval().getStatus(),
                                row.slot().getRejectReason())))
                .toList();
        boolean anyBooked = options.stream().anyMatch(option ->
                option.state() == SlackSchedulingViews.OptionState.BOOKED);
        return new ComputedUpdate(target.channelId(), target.ts(),
                SlackSchedulingViews.combinedFallback(options.size(), 0, anyBooked),
                SlackSchedulingViews.combinedProposalCard(request, options,
                        names.candidateName(), names.positionTitle(), 0));
    }

    public static Names namesOf(RecruitmentSchedulingRequest request) {
        RecruitmentApplication application =
                RecruitmentApplication.findById(request.getApplicationUuid());
        RecruitmentCandidate candidate = application == null ? null
                : RecruitmentCandidate.findById(application.getCandidateUuid());
        RecruitmentPosition position = application == null ? null
                : RecruitmentPosition.findById(application.getPositionUuid());
        String name = candidate == null ? "kandidaten"
                : ((candidate.getFirstName() == null ? "" : candidate.getFirstName())
                        + " " + (candidate.getLastName() == null ? "" : candidate.getLastName()))
                        .trim();
        return new Names(name.isEmpty() ? "kandidaten" : name,
                position != null ? position.getTitle() : null);
    }
}
