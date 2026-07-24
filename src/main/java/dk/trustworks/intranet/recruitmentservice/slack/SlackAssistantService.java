package dk.trustworks.intranet.recruitmentservice.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.recruitmentservice.ai.AiAssistantPrompts;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPositionStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.notifications.RecruitmentSlackChannel;
import dk.trustworks.intranet.recruitmentservice.notifications.SlackCandidateFacts;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentInterviewService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The P25 @Recruiting assistant (Slack spec §5.11): answers factual
 * status questions asked by mentioning the bot, in a threaded reply.
 * Runs OFF the dispatch transaction (submitted to a {@code ManagedExecutor}
 * by {@link SlackAssistantMentionHandler}) because the intent parse is an
 * OpenAI round-trip — the §P9 M1 rule (no model call while a transaction
 * holds a pooled connection) and Slack's 3-second events ack both forbid
 * doing this work inline.
 *
 * <h3>The assistive-only construction</h3>
 * OpenAI's ONLY job is the intent parse ({@code store=false}, strict
 * schema: intent + candidate/position reference). The reply prose is
 * composed <b>deterministically</b> from structural facts — stage,
 * days-in-stage, scorecards-in <em>count</em>, next interview, last
 * activity <em>type</em> — read through the actor's exact P8 read
 * authorization ({@link SlackCandidateSearch} + {@link RecruitmentVisibility}).
 * Nothing the model writes ever reaches Slack; note text, scorecard prose
 * and application answers are never read at all. Evaluative questions and
 * action requests are refused with a standard pointer — the assistant is
 * read-only by construction: it dispatches no commands.
 *
 * <h3>Surface confidentiality (the P22 rule, applied inbound)</h3>
 * Partner-track (CIRCLE) content never renders into a shared channel —
 * even for an authorized actor, because a channel reply is visible to the
 * whole channel. It is answerable in a DM with the bot and inside the
 * position's own private {@code recr-*} channel (whose membership IS the
 * circle). Everywhere else, partner-only matches answer the same uniform
 * no-access/no-match sentence — existence never leaks.
 *
 * <h3>Spot-review log</h3>
 * Every exchange appends {@code AI_ASSISTANT_EXCHANGE}: the question in
 * {@code pii} (it can name a person), the answer <em>skeleton</em> in
 * {@code payload} (intent, outcome, fact kinds — never the reply prose).
 * Single-match answers set the candidate subject so the GDPR anonymizer
 * scrubs the question along with the candidate's other pii.
 */
@JBossLog
@ApplicationScoped
public class SlackAssistantService {

    /** Spec-locked footer — on every fact-bearing answer (plan §P25 DoD). */
    static final String FOOTER = "_AI — verify in profile_";

    /** Outcomes recorded in {@code payload.outcome}. */
    static final String OUTCOME_ANSWERED = "ANSWERED";
    static final String OUTCOME_AMBIGUOUS = "AMBIGUOUS";
    static final String OUTCOME_NO_MATCH = "NO_MATCH";
    static final String OUTCOME_REFUSED_EVALUATIVE = "REFUSED_EVALUATIVE";
    static final String OUTCOME_REFUSED_ACTION = "REFUSED_ACTION";
    static final String OUTCOME_HELP = "HELP";
    static final String OUTCOME_FAILED = "FAILED";

    /** Candidate/position matches shown before asking to narrow down. */
    static final int MAX_MATCHES = 5;
    /** The module's Slack text clamp (P12) — a shorter reply beats a lost one. */
    static final int REPLY_MAX = 3000;
    /** The logged question is capped — spot review needs the gist, not a novel. */
    static final int QUESTION_LOG_MAX = 1000;

    /** Interview times are wall-clock Europe/Copenhagen (the P11 model). */
    private static final DateTimeFormatter INTERVIEW_TIME =
            DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);

    @Inject
    OpenAIService openAIService;

    @Inject
    SlackCandidateSearch candidateSearch;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    SlackService slackService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "dk.trustworks.recruitment.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    /** The intent parse result — the ONLY thing the model contributes. */
    record ParsedIntent(String intent, String candidateReference, String positionReference) {
    }

    /** One composed reply plus the skeleton the exchange log records. */
    record Composed(String reply, String outcome, String candidateUuid, String positionUuid,
                    boolean circleContent, int matchCount, List<String> facts) {

        static Composed of(String reply, String outcome) {
            return new Composed(reply, outcome, null, null, false, 0, List.of());
        }
    }

    /** Where the mention happened — drives the partner-content rule. */
    private record Surface(String kind, String partnerPositionUuid) {
        static final String DM = "dm";
        static final String PARTNER_CHANNEL = "partner_channel";
        static final String SHARED = "channel";

        boolean allowsPartnerContent(String positionUuid) {
            return DM.equals(kind)
                    || (PARTNER_CHANNEL.equals(kind) && Objects.equals(partnerPositionUuid, positionUuid));
        }
    }

    /**
     * Answer one mention. Called on the {@code ManagedExecutor} — never
     * inside a caller transaction. Best-effort end to end: every failure
     * is logged (content-free), answered with a generic apology and — when
     * possible — recorded as a FAILED exchange.
     */
    public void answerMention(String actorUuid, String channelId, String threadTs, String rawText) {
        if (channelId == null || channelId.isBlank()) {
            log.warn("Assistant mention without a channel id — nothing to reply into, dropped");
            return;
        }
        String question = stripMentionTokens(rawText);
        try {
            ParsedIntent intent = question.isBlank()
                    ? new ParsedIntent(AiAssistantPrompts.INTENT_OTHER, null, null)
                    : parseIntent(question);
            Composed composed = QuarkusTransaction.requiringNew()
                    .call(() -> compose(actorUuid, channelId, intent));
            reply(channelId, threadTs, composed.reply());
            QuarkusTransaction.requiringNew()
                    .run(() -> logExchange(actorUuid, channelId, question, intent, composed));
        } catch (Exception e) {
            // Content-free by the module's PII log discipline.
            log.errorf(e, "Assistant exchange failed (surface=%s)", channelKind(channelId));
            reply(channelId, threadTs,
                    "Sorry — something went wrong while I was looking that up. "
                            + "Please try again, or check directly: "
                            + link(baseUrl + "/recruitment", "Open recruitment"));
            try {
                QuarkusTransaction.requiringNew().run(() -> logExchange(actorUuid, channelId,
                        question, new ParsedIntent(AiAssistantPrompts.INTENT_OTHER, null, null),
                        Composed.of("", OUTCOME_FAILED)));
            } catch (Exception logFailed) {
                log.error("Assistant exchange failure could not be logged either", logFailed);
            }
        }
    }

    // ------------------------------------------------------------------
    // Phase 1 — intent parse (OpenAI, untransacted, store=false)
    // ------------------------------------------------------------------

    ParsedIntent parseIntent(String question) {
        if (QuarkusTransaction.isActive()) {
            // The §P9 M1 rule: never hold a pooled connection across the model call.
            throw new IllegalStateException("parseIntent must not be called inside a transaction");
        }
        String json = openAIService.askQuestionWithSchema(
                AiAssistantPrompts.systemPrompt(),
                AiAssistantPrompts.userPrompt(question),
                AiAssistantPrompts.schema(),
                "assistant_intent",
                AiAssistantPrompts.REFUSAL_FALLBACK_JSON,
                null, 0, false);
        try {
            JsonNode node = objectMapper.readTree(json);
            String intent = node.path("intent").asText(AiAssistantPrompts.INTENT_OTHER);
            if (!Set.of(AiAssistantPrompts.INTENT_CANDIDATE_STATUS,
                    AiAssistantPrompts.INTENT_POSITION_STATUS,
                    AiAssistantPrompts.INTENT_EVALUATIVE,
                    AiAssistantPrompts.INTENT_ACTION_REQUEST,
                    AiAssistantPrompts.INTENT_OTHER).contains(intent)) {
                intent = AiAssistantPrompts.INTENT_OTHER;
            }
            return new ParsedIntent(intent,
                    clampReference(node.path("candidate_reference")),
                    clampReference(node.path("position_reference")));
        } catch (Exception e) {
            log.warn("Assistant intent parse returned unusable JSON — answering help", e);
            return new ParsedIntent(AiAssistantPrompts.INTENT_OTHER, null, null);
        }
    }

    private static String clampReference(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    // ------------------------------------------------------------------
    // Phase 2 — deterministic composition (one read-only transaction)
    // ------------------------------------------------------------------

    Composed compose(String actorUuid, String channelId, ParsedIntent intent) {
        Surface surface = resolveSurface(channelId);
        return switch (intent.intent()) {
            case AiAssistantPrompts.INTENT_EVALUATIVE -> Composed.of(
                    "That's a judgement call I deliberately don't make. I can tell you where a "
                            + "candidate stands — stage, what's waiting, the next interview — but "
                            + "assessments, comparisons and hire recommendations belong to the humans "
                            + "in the debrief. You'll find scorecards and the decision flow on the "
                            + "candidate's profile: " + link(baseUrl + "/recruitment", "Open recruitment"),
                    OUTCOME_REFUSED_EVALUATIVE);
            case AiAssistantPrompts.INTENT_ACTION_REQUEST -> Composed.of(
                    "I'm read-only — I can't move, reject, schedule or change anything, no matter "
                            + "how the request is phrased. I can tell you where things stand; actions "
                            + "happen in the intranet: " + link(baseUrl + "/recruitment", "Open recruitment"),
                    OUTCOME_REFUSED_ACTION);
            case AiAssistantPrompts.INTENT_CANDIDATE_STATUS ->
                    candidateStatus(actorUuid, surface, intent.candidateReference());
            case AiAssistantPrompts.INTENT_POSITION_STATUS ->
                    positionStatus(actorUuid, surface, intent.positionReference());
            default -> Composed.of(helpText(), OUTCOME_HELP);
        };
    }

    private String helpText() {
        return "Hi! Ask me where a candidate or position stands — for example "
                + "_\"where are we with Jens Hansen?\"_ or _\"status on the Senior Consultant "
                + "position?\"_. I answer with facts only — stage, what's waiting, the next "
                + "interview — and only what you're authorized to see.";
    }

    // ---- Candidate status ---------------------------------------------

    private Composed candidateStatus(String actorUuid, Surface surface, String reference) {
        if (reference == null || reference.isBlank()) {
            return Composed.of("Tell me who you mean — for example "
                    + "_\"where are we with Jens Hansen?\"_", OUTCOME_HELP);
        }
        List<RecruitmentCandidate> hits = candidateSearch.search(actorUuid, reference, MAX_MATCHES);

        // Surface-filter each hit: partner-track (CIRCLE) applications only
        // render where the surface allows them; a candidate whose visible
        // footprint is partner-only becomes a no-match on a shared surface.
        List<CandidateView> views = new ArrayList<>();
        for (RecruitmentCandidate candidate : hits) {
            CandidateView view = candidateView(actorUuid, surface, candidate);
            if (view != null) {
                views.add(view);
            }
        }
        String safeReference = SlackCandidateFacts.mrkdwnSafe(reference);
        if (views.isEmpty()) {
            // The lookup handler's uniform sentence — no access and no match
            // answer the same words, existence never leaks (spec §5.11).
            return Composed.of("No candidates matching \"" + safeReference
                    + "\" that you have access to.", OUTCOME_NO_MATCH);
        }
        if (views.size() > 1) {
            return disambiguation(safeReference, views);
        }
        return candidateAnswer(views.getFirst());
    }

    /** One candidate as the actor may see them on this surface; null = hidden here. */
    private record CandidateView(RecruitmentCandidate candidate,
                                 List<ApplicationLine> openLines,
                                 boolean hadAnyApplications,
                                 boolean circleContent) {
    }

    private record ApplicationLine(RecruitmentApplication application,
                                   RecruitmentPosition position,
                                   boolean partnerTrack) {
    }

    private CandidateView candidateView(String actorUuid, Surface surface,
                                        RecruitmentCandidate candidate) {
        List<RecruitmentApplication> applications =
                visibility.filterApplications(actorUuid, candidate.getUuid());
        List<ApplicationLine> allowed = new ArrayList<>();
        boolean suppressedPartner = false;
        boolean circleContent = false;
        for (RecruitmentApplication application : applications) {
            RecruitmentPosition position = RecruitmentPosition.findById(application.getPositionUuid());
            boolean partner = position != null
                    && position.getHiringTrack() == RecruitmentHiringTrack.PARTNER;
            if (partner && !surface.allowsPartnerContent(application.getPositionUuid())) {
                suppressedPartner = true;
                continue;
            }
            circleContent |= partner;
            allowed.add(new ApplicationLine(application, position, partner));
        }
        // Partner-only on a disallowed surface: the candidate's entire
        // visible existence would be circle content — uniform no-match.
        if (allowed.isEmpty() && suppressedPartner) {
            return null;
        }
        List<ApplicationLine> openLines = allowed.stream()
                .filter(line -> line.application().getTerminal() == null)
                .toList();
        return new CandidateView(candidate, openLines, !applications.isEmpty(), circleContent);
    }

    private Composed disambiguation(String safeReference, List<CandidateView> views) {
        StringBuilder sb = new StringBuilder(256)
                .append("I found ").append(views.size()).append(" candidates matching \"")
                .append(safeReference).append("\" — which one do you mean?");
        boolean circleContent = false;
        for (CandidateView view : views) {
            circleContent |= view.circleContent();
            sb.append("\n• *").append(SlackCandidateFacts.mrkdwnSafe(candidateName(view.candidate())))
                    .append("* — ").append(oneLineStatus(view)).append(" — ")
                    .append(profileLink(view.candidate().getUuid(), "profile"));
        }
        sb.append("\nMention me again with the full name.");
        sb.append("\n").append(FOOTER);
        return new Composed(clamp(sb.toString()), OUTCOME_AMBIGUOUS, null, null,
                circleContent, views.size(), List.of());
    }

    private Composed candidateAnswer(CandidateView view) {
        RecruitmentCandidate candidate = view.candidate();
        List<String> facts = new ArrayList<>();
        StringBuilder sb = new StringBuilder(256)
                .append("*").append(SlackCandidateFacts.mrkdwnSafe(candidateName(candidate)))
                .append("*");
        if (view.openLines().isEmpty()) {
            sb.append(" — ").append(closedStatusLine(candidate, view.hadAnyApplications()));
            facts.add("status");
        } else {
            sb.append(" — here's where things stand:");
            for (ApplicationLine line : view.openLines()) {
                appendOpenApplication(sb, line, facts);
            }
        }
        sb.append("\n").append(profileLink(candidate.getUuid(), "Open profile"));
        sb.append("\n").append(FOOTER);
        return new Composed(clamp(sb.toString()), OUTCOME_ANSWERED, candidate.getUuid(), null,
                view.circleContent(), 1, List.copyOf(new LinkedHashSet<>(facts)));
    }

    private void appendOpenApplication(StringBuilder sb, ApplicationLine line, List<String> facts) {
        RecruitmentApplication application = line.application();
        RecruitmentPosition position = line.position();
        sb.append("\n• ")
                .append(position == null ? "Unknown position"
                        : "*" + SlackCandidateFacts.mrkdwnSafe(position.getTitle()) + "*")
                .append(" — ").append(SlackHandlerSupport.humanizeStage(application.getStage().name()))
                .append(" · ").append(daysInStage(application.getStageEnteredAt())).append(" in stage");
        facts.add("stage");
        facts.add("days_in_stage");

        List<RecruitmentInterview> interviews = RecruitmentInterview.list(
                "applicationUuid = ?1 and status <> ?2",
                application.getUuid(), RecruitmentInterviewStatus.CANCELLED);
        String waitingOn = waitingOn(application, interviews, facts);
        String lastActivity = lastActivity(application.getUuid());
        sb.append("\n   ↳ ").append(waitingOn);
        if (lastActivity != null) {
            sb.append(" · Last activity: ").append(lastActivity);
            facts.add("last_activity");
        }
    }

    /**
     * The waiting-on fact, derived with the P17 rules: an unfinished round
     * waits on its scorecards (count only — the blind rule keeps scores and
     * names out), a finished one on the decision, otherwise the next
     * scheduled interview or a generic next step.
     */
    private String waitingOn(RecruitmentApplication application,
                             List<RecruitmentInterview> interviews, List<String> facts) {
        RecruitmentInterview currentRound = interviews.stream()
                .filter(i -> i.getKind() == RecruitmentInterviewKind.ROUND)
                .filter(i -> i.roundStage() == application.getStage())
                .max(Comparator.comparing(RecruitmentInterview::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
        if (currentRound != null && currentRound.getStatus() == RecruitmentInterviewStatus.HELD) {
            List<RecruitmentScorecard> cards =
                    RecruitmentScorecard.list("interviewUuid", currentRound.getUuid());
            List<String> assigned = currentRound.getInterviewerUuids();
            int expected = assigned == null ? 0 : assigned.size();
            if (RecruitmentInterviewService.allAssignedSubmitted(currentRound, cards)) {
                facts.add("waiting_on");
                return "Debrief ready — decision pending";
            }
            long submitted = cards.stream().map(RecruitmentScorecard::getInterviewerUuid)
                    .filter(Objects::nonNull).distinct().count();
            facts.add("waiting_on");
            return "Waiting on " + Math.max(expected - submitted, 1) + " of " + expected
                    + " scorecard" + (expected == 1 ? "" : "s");
        }
        RecruitmentInterview nextScheduled = interviews.stream()
                .filter(i -> i.getStatus() == RecruitmentInterviewStatus.SCHEDULED)
                .filter(i -> i.getScheduledAt() != null)
                .min(Comparator.comparing(RecruitmentInterview::getScheduledAt))
                .orElse(null);
        if (nextScheduled != null) {
            facts.add("next_interview");
            return "Next interview: " + INTERVIEW_TIME.format(nextScheduled.getScheduledAt());
        }
        facts.add("waiting_on");
        return "Waiting on the next step";
    }

    /**
     * Last activity = the newest event on the application — TYPE and age
     * only, never content (a note's existence is structural; its text
     * stays in pii and never surfaces here).
     */
    private String lastActivity(String applicationUuid) {
        RecruitmentEvent latest = RecruitmentEvent
                .<RecruitmentEvent>find("applicationUuid = ?1", Sort.descending("seq"), applicationUuid)
                .firstResult();
        if (latest == null) {
            return null;
        }
        return SlackCandidateFacts.humanizeCode(latest.getEventType().name())
                + " (" + relativeDays(latest.getOccurredAt()) + ")";
    }

    private String oneLineStatus(CandidateView view) {
        if (view.openLines().isEmpty()) {
            return closedStatusLine(view.candidate(), view.hadAnyApplications());
        }
        ApplicationLine first = view.openLines().getFirst();
        String position = first.position() == null ? "unknown position"
                : SlackCandidateFacts.mrkdwnSafe(first.position().getTitle());
        String stage = SlackHandlerSupport.humanizeStage(first.application().getStage().name());
        return stage + " (" + position + ")"
                + (view.openLines().size() > 1
                        ? " +" + (view.openLines().size() - 1) + " more" : "");
    }

    /** Mirrors the {@code /candidates} lookup's status wording (P14). */
    private static String closedStatusLine(RecruitmentCandidate candidate, boolean hadApplications) {
        return switch (candidate.getStatus()) {
            case POOLED -> "in the talent pool";
            case HIRED -> "hired";
            case DECLINED -> "not proceeding (declined)";
            case WITHDRAWN -> "withdrew";
            default -> hadApplications ? "no open applications" : "no applications yet";
        };
    }

    // ---- Position status ----------------------------------------------

    private Composed positionStatus(String actorUuid, Surface surface, String reference) {
        if (reference == null || reference.isBlank()) {
            return Composed.of("Tell me which position you mean — for example "
                    + "_\"status on the Senior Consultant position?\"_", OUTCOME_HELP);
        }
        String like = "%" + SlackCandidateSearch.escapeLike(reference.toLowerCase()) + "%";
        List<RecruitmentPosition> scanned = RecruitmentPosition
                .<RecruitmentPosition>find("LOWER(title) LIKE ?1 escape '\\'",
                        Sort.descending("createdAt"), like)
                .page(Page.ofSize(50))
                .list();
        List<RecruitmentPosition> matches = new ArrayList<>();
        for (RecruitmentPosition position : scanned) {
            if (matches.size() >= MAX_MATCHES) {
                break;
            }
            if (!visibility.canReadPosition(actorUuid, position)) {
                continue;
            }
            // The same surface rule as candidates: a partner-track position
            // is confidential — never rendered into a shared channel.
            if (position.getHiringTrack() == RecruitmentHiringTrack.PARTNER
                    && !surface.allowsPartnerContent(position.getUuid())) {
                continue;
            }
            matches.add(position);
        }
        String safeReference = SlackCandidateFacts.mrkdwnSafe(reference);
        if (matches.isEmpty()) {
            return Composed.of("No positions matching \"" + safeReference
                    + "\" that you have access to.", OUTCOME_NO_MATCH);
        }
        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder(256)
                    .append("I found ").append(matches.size()).append(" positions matching \"")
                    .append(safeReference).append("\" — which one do you mean?");
            boolean circle = false;
            for (RecruitmentPosition position : matches) {
                circle |= position.getHiringTrack() == RecruitmentHiringTrack.PARTNER;
                sb.append("\n• *").append(SlackCandidateFacts.mrkdwnSafe(position.getTitle()))
                        .append("* — ").append(position.getStatus() == RecruitmentPositionStatus.OPEN
                                ? "open" : "closed");
            }
            sb.append("\nMention me again with the full title.");
            sb.append("\n").append(FOOTER);
            return new Composed(clamp(sb.toString()), OUTCOME_AMBIGUOUS, null, null,
                    circle, matches.size(), List.of());
        }
        return positionAnswer(actorUuid, matches.getFirst());
    }

    private Composed positionAnswer(String actorUuid, RecruitmentPosition position) {
        boolean partner = position.getHiringTrack() == RecruitmentHiringTrack.PARTNER;
        List<String> facts = new ArrayList<>(List.of("position_status"));
        StringBuilder sb = new StringBuilder(256)
                .append("*").append(SlackCandidateFacts.mrkdwnSafe(position.getTitle())).append("*")
                .append(" — ").append(position.getStatus() == RecruitmentPositionStatus.OPEN
                        ? "open" : "closed");
        if (position.getStatus() == RecruitmentPositionStatus.OPEN) {
            List<RecruitmentApplication> open = RecruitmentApplication
                    .<RecruitmentApplication>list("positionUuid = ?1 and terminal is null",
                            position.getUuid())
                    .stream()
                    .filter(application -> visibility.canReadApplication(actorUuid, application))
                    .toList();
            if (open.isEmpty()) {
                sb.append("\nNo candidates in play that you have access to.");
            } else {
                Map<RecruitmentStage, Long> byStage = new EnumMap<>(RecruitmentStage.class);
                for (RecruitmentApplication application : open) {
                    byStage.merge(application.getStage(), 1L, Long::sum);
                }
                sb.append("\n").append(open.size()).append(open.size() == 1
                        ? " candidate in play: " : " candidates in play: ");
                sb.append(String.join(", ", byStage.entrySet().stream()
                        .map(e -> e.getValue() + " in "
                                + SlackHandlerSupport.humanizeStage(e.getKey().name()))
                        .toList()));
                facts.add("stage_counts");
            }
        }
        sb.append("\n").append(link(baseUrl + "/recruitment/pipeline", "Open pipeline board"));
        sb.append("\n").append(FOOTER);
        return new Composed(clamp(sb.toString()), OUTCOME_ANSWERED, null, position.getUuid(),
                partner, 1, facts);
    }

    // ------------------------------------------------------------------
    // Phase 3 — the spot-review log (fresh short transaction)
    // ------------------------------------------------------------------

    /**
     * Appends the {@code AI_ASSISTANT_EXCHANGE} event: question in pii,
     * answer skeleton in payload. Candidate/position subjects only on
     * single-match answers; CIRCLE visibility whenever the reply carried
     * partner-track content.
     */
    void logExchange(String actorUuid, String channelId, String question,
                     ParsedIntent intent, Composed composed) {
        RecruitmentEventBuilder builder = RecruitmentEventBuilder
                .event(RecruitmentEventType.AI_ASSISTANT_EXCHANGE)
                .actorUser(actorUuid)
                .candidate(composed.candidateUuid())
                .position(composed.positionUuid())
                .visibility(composed.circleContent()
                        ? RecruitmentEventVisibility.CIRCLE : RecruitmentEventVisibility.NORMAL)
                .payload("origin", "slack")
                .payload("intent", intent.intent())
                .payload("outcome", composed.outcome())
                .payload("channel_kind", channelKind(channelId))
                .payload("match_count", composed.matchCount())
                .payload("prompt_version", AiAssistantPrompts.PROMPT_VERSION);
        if (!composed.facts().isEmpty()) {
            builder.payload("facts", composed.facts());
        }
        if (question != null && !question.isBlank()) {
            builder.pii("question", question.length() <= QUESTION_LOG_MAX
                    ? question : question.substring(0, QUESTION_LOG_MAX));
        }
        eventRecorder.record(builder);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Surface resolveSurface(String channelId) {
        if (channelId.startsWith("D")) {
            return new Surface(Surface.DM, null);
        }
        RecruitmentSlackChannel partnerChannel = RecruitmentSlackChannel
                .<RecruitmentSlackChannel>find("channelId = ?1 and archivedAt is null", channelId)
                .firstResult();
        if (partnerChannel != null) {
            return new Surface(Surface.PARTNER_CHANNEL, partnerChannel.getPositionUuid());
        }
        return new Surface(Surface.SHARED, null);
    }

    private String channelKind(String channelId) {
        if (channelId == null || !channelId.startsWith("D")) {
            // The partner-channel refinement needs a DB read; for logging
            // outside a transaction "channel" is precise enough.
            return "channel";
        }
        return "dm";
    }

    /** Threaded when the mention carried a ts; plain channel post otherwise. */
    private void reply(String channelId, String threadTs, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (threadTs != null && !threadTs.isBlank()) {
            slackService.sendThreadReply(channelId, threadTs, text);
        } else {
            slackService.sendMessage(channelId, text);
        }
    }

    /** Strips {@code <@U…>} mention tokens and Slack specials, collapses whitespace. */
    static String stripMentionTokens(String rawText) {
        if (rawText == null) {
            return "";
        }
        return rawText
                .replaceAll("<@[A-Z0-9]+(\\|[^>]*)?>", " ")
                .replaceAll("<![a-z]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String candidateName(RecruitmentCandidate candidate) {
        String name = ((candidate.getFirstName() == null ? "" : candidate.getFirstName()) + " "
                + (candidate.getLastName() == null ? "" : candidate.getLastName())).trim();
        return name.isEmpty() ? "Unnamed candidate" : name;
    }

    private String profileLink(String candidateUuid, String label) {
        return link(baseUrl + "/recruitment/candidates/" + candidateUuid, label);
    }

    private static String link(String url, String label) {
        return "<" + url + "|" + label + ">";
    }

    private static String daysInStage(LocalDateTime stageEnteredAt) {
        if (stageEnteredAt == null) {
            return "? days";
        }
        long days = ChronoUnit.DAYS.between(stageEnteredAt, LocalDateTime.now(ZoneOffset.UTC));
        if (days <= 0) {
            return "today";
        }
        return days == 1 ? "1 day" : days + " days";
    }

    private static String relativeDays(LocalDateTime occurredAtUtc) {
        if (occurredAtUtc == null) {
            return "unknown";
        }
        long days = ChronoUnit.DAYS.between(occurredAtUtc, LocalDateTime.now(ZoneOffset.UTC));
        if (days <= 0) {
            return "today";
        }
        return days == 1 ? "yesterday" : days + " days ago";
    }

    private static String clamp(String message) {
        return message.length() <= REPLY_MAX ? message : message.substring(0, REPLY_MAX - 1) + "…";
    }
}
