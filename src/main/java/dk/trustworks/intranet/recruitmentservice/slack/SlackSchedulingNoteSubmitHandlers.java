package dk.trustworks.intranet.recruitmentservice.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityMessageService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.SchedulingAsyncRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * The note modals' submits (plan §9.1): route the interviewer's plain
 * note verbatim to the recruiter as a DM, wrapped in a deterministic
 * template naming the sender and the slot — never model prose (D6).
 * The note text is PII: it lands in the recruiter DM and the event's
 * {@code pii} block, nowhere else.
 * <p>
 * The DM is sent synchronously (the openView precedent — one Slack call
 * inside the dispatch window): on failure the interviewer is told to
 * contact the recruitment team directly, and NOTHING is recorded — a
 * logged note the recruiter never received would lie to the audit trail.
 * <p>
 * The "Foreslå anden tid" note ADDITIONALLY feeds the Phase 12
 * availability pipeline after the dispatch commits (F7 — the button the
 * interviewer is most likely to press must not discard what they wrote;
 * new cards no longer carry the button, but delivered ones do).
 */
public final class SlackSchedulingNoteSubmitHandlers {

    private SlackSchedulingNoteSubmitHandlers() {
    }

    abstract static class NoteSubmitHandler implements SlackInboundHandler {

        @Inject
        RecruitmentSchedulingFeatureFlag methodBFlag;

        @Inject
        SlackSchedulingSupport support;

        @Inject
        SlackService slackService;

        @Inject
        RecruitmentEventRecorder eventRecorder;

        @Inject
        ObjectMapper objectMapper;

        /** The structural note kind for the audit payload. */
        abstract String noteKind();

        /** The deterministic DM headline (Danish). */
        abstract String headline();

        /** Whether the note also feeds the availability pipeline (F7). */
        boolean feedsAvailability() {
            return false;
        }

        @Inject
        SchedulingAsyncRunner asyncRunner;

        @Inject
        AvailabilityMessageService messageService;

        @Override
        public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
            if (!methodBFlag.isMethodBEnabled()) {
                return SlackInboundResponse.disabled(SlackInboundDispatchService.DISABLED_TEXT);
            }
            SlackSchedulingSupport.Resolved resolved =
                    support.resolve(actor, request.privateMetadata());
            if (resolved.deny() != null) {
                return resolved.deny();
            }
            String note = SlackViewState.parse(objectMapper, request.stateValues())
                    .text(SlackSchedulingViews.BLOCK_NOTE);
            if (note == null) {
                return SlackInboundResponse.handledWithAction(
                        SlackResponseActions.errors(
                                java.util.Map.of(SlackSchedulingViews.BLOCK_NOTE,
                                        "Skriv en besked først")));
            }
            User recruiter = User.findById(resolved.request().getRecruiterUuid());
            if (recruiter == null || recruiter.getSlackusername() == null
                    || recruiter.getSlackusername().isBlank()) {
                return SlackInboundResponse.handled(
                        "Rekrutteringsteamet kunne ikke findes på Slack — "
                                + "kontakt dem direkte.");
            }
            String message = ":speech_balloon: *" + headline() + "*\n"
                    + "Fra " + SlackHandlerSupport.displayName(actor)
                    + " om forslaget *"
                    + SlackSchedulingViews.danishInterval(
                            resolved.slot().getSlotStart(), resolved.slot().getSlotEnd())
                    + "* (" + SlackCandidateFactsSafe.name(resolved.candidateName()) + "):\n"
                    + "> " + note.replace("\n", "\n> ");
            try {
                slackService.sendMessage(recruiter, headline(), java.util.List.of(
                        com.slack.api.model.block.Blocks.section(
                                s -> s.text(com.slack.api.model.block.composition
                                        .BlockCompositions.markdownText(message)))));
            } catch (Exception e) {
                return SlackInboundResponse.handled(
                        "Beskeden kunne ikke sendes — kontakt rekrutteringsteamet direkte.");
            }

            eventRecorder.record(RecruitmentEventBuilder
                    .event(RecruitmentEventType.SCHEDULING_NOTE_ROUTED)
                    .application(resolved.request().getApplicationUuid())
                    .candidate(resolved.application() != null
                            ? resolved.application().getCandidateUuid() : null)
                    .position(resolved.application() != null
                            ? resolved.application().getPositionUuid() : null)
                    .actorUser(actor.getUuid())
                    .payload("request_uuid", resolved.request().getUuid())
                    .payload("slot_uuid", resolved.slot().getUuid())
                    .payload("note_kind", noteKind())
                    .payload("origin", "slack")
                    .pii("note", note));

            if (feedsAvailability()) {
                // F7: what the interviewer wrote is availability — run it
                // through the Phase 12 pipeline after this dispatch
                // commits, answering in the proposal DM's channel.
                String actorUuid = actor.getUuid();
                String channelId = resolved.approval().getSlackChannelId();
                String requestUuid = resolved.request().getUuid();
                asyncRunner.submitAfterCommit(() ->
                        messageService.processNote(actorUuid, channelId, requestUuid, note));
                return SlackInboundResponse.handled(
                        "Modtaget — jeg læser dine tider og er tilbage om et øjeblik. "
                                + "Beskeden er også sendt til rekrutteringsteamet.");
            }
            return SlackInboundResponse.handled("Beskeden er sendt til rekrutteringsteamet.");
        }
    }

    /** mrkdwn-escape without dragging the facts helper's null handling. */
    static final class SlackCandidateFactsSafe {
        private SlackCandidateFactsSafe() {
        }

        static String name(String name) {
            return dk.trustworks.intranet.recruitmentservice.notifications
                    .SlackCandidateFacts.mrkdwnSafe(name == null ? "kandidaten" : name);
        }
    }

    /** "Foreslå anden tid" submit. */
    @JBossLog
    @ApplicationScoped
    public static class Suggest extends NoteSubmitHandler {
        @Override
        public String key() {
            return SlackSchedulingViews.SUGGEST_SUBMIT;
        }

        @Override
        String noteKind() {
            return "SUGGEST_OTHER_TIME";
        }

        @Override
        String headline() {
            return "Forslag om anden interviewtid";
        }

        @Override
        boolean feedsAvailability() {
            return true;
        }
    }

    /** "Spørg rekruttereren" submit. */
    @JBossLog
    @ApplicationScoped
    public static class Ask extends NoteSubmitHandler {
        @Override
        public String key() {
            return SlackSchedulingViews.ASK_SUBMIT;
        }

        @Override
        String noteKind() {
            return "QUESTION";
        }

        @Override
        String headline() {
            return "Spørgsmål om interviewforslag";
        }
    }
}
