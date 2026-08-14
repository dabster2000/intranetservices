package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * The proposal card's two note buttons (plan §9.1): until the Phase 12
 * NLU exists, <b>Foreslå anden tid</b> and <b>Spørg rekruttereren</b>
 * open a plain note modal that routes verbatim to the recruiter. The
 * approval uuid rides in {@code private_metadata} (a claim — the submit
 * handlers re-authorize). Neither button changes any scheduling state:
 * the proposal stays open until answered with Godkend/Afvis.
 */
public final class SlackSchedulingNoteButtonHandlers {

    private SlackSchedulingNoteButtonHandlers() {
    }

    /** Shared open-the-modal flow of both buttons. */
    abstract static class NoteButtonHandler implements SlackInboundHandler {

        @Inject
        RecruitmentSchedulingFeatureFlag methodBFlag;

        @Inject
        SlackSchedulingSupport support;

        @Inject
        SlackService slackService;

        abstract com.slack.api.model.view.View modal(String privateMetadata);

        @Override
        public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
            if (!methodBFlag.isMethodBEnabled()) {
                return SlackInboundResponse.disabled(SlackInboundDispatchService.DISABLED_TEXT);
            }
            SlackSchedulingSupport.Resolved resolved =
                    support.resolve(actor, request.actionValue());
            if (resolved.deny() != null) {
                return resolved.deny();
            }
            if (!resolved.slot().getStatus().isLive()) {
                return SlackInboundResponse.handled(SlackSchedulingSupport.GONE_TEXT);
            }
            try {
                slackService.openView(request.triggerId(),
                        modal(resolved.approval().getUuid()));
                return SlackInboundResponse.handled(null);
            } catch (Exception e) {
                return SlackInboundResponse.handled(
                        "Beskedvinduet kunne ikke åbnes — prøv igen.");
            }
        }
    }

    /** "Foreslå anden tid" — opens the suggestion note modal. */
    @JBossLog
    @ApplicationScoped
    public static class Suggest extends NoteButtonHandler {
        @Override
        public String key() {
            return SlackSchedulingViews.ACTION_SUGGEST;
        }

        @Override
        com.slack.api.model.view.View modal(String privateMetadata) {
            return SlackSchedulingViews.suggestModal(privateMetadata);
        }
    }

    /** "Spørg rekruttereren" — opens the question note modal. */
    @JBossLog
    @ApplicationScoped
    public static class Ask extends NoteButtonHandler {
        @Override
        public String key() {
            return SlackSchedulingViews.ACTION_ASK;
        }

        @Override
        com.slack.api.model.view.View modal(String privateMetadata) {
            return SlackSchedulingViews.askModal(privateMetadata);
        }
    }
}
