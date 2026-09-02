package dk.trustworks.intranet.recruitmentservice.ai;

import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailBodyFormat;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentAiVoiceCard;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailHtmlSanitizer;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailRenderer;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Set;

/**
 * The P16 AI email composer (AI spec §5.4, plan §P16): one synchronous
 * OpenAI round-trip that personalises a rendered Danish template body for
 * one candidate, honouring an optional recruiter instruction. Returns a
 * DRAFT only — <b>there is no send path here, by design</b>: the draft
 * lands pre-filled in the compose dialog and the send stays the
 * recruiter's explicit action through the P15 manual-send contract
 * ({@link RecruitmentEmailService#sendManual}), independent of the
 * template's {@code auto_send} setting.
 * <p>
 * Model inputs are deliberately limited to the rendered template, the
 * candidate's first name, the position title, the current stage and the
 * instruction — never scorecards or notes (rejection texts must not leak
 * assessment material). The system prompt additionally carries the
 * Trustworks tone-of-voice card when one is configured
 * ({@link RecruitmentAiVoiceCard}) — operator-authored style guidance, read
 * with the other inputs in phase 1 so the untransacted OpenAI leg makes no
 * DB call.
 * <p>
 * Transaction posture mirrors {@link AiIntakeGenerationService}'s
 * M1-safe variant: inputs are gathered in a short completed transaction,
 * the OpenAI round-trip runs with no transaction active (no pooled DB
 * connection held across the ~110 s read timeout), and the
 * {@code AI_EMAIL_DRAFT_GENERATED} bookkeeping event is appended in a
 * fresh transaction. Every call passes {@code store=false} (candidate
 * PII goes to OpenAI, never into Responses storage or logs).
 */
@JBossLog
@ApplicationScoped
public class AiEmailDraftService {

    /** Recruiter instructions are one short sentence — hard input cap. */
    public static final int INSTRUCTION_MAX_LENGTH = 500;

    /**
     * Output budget for the body draft. Template bodies are ≤ 10 000 chars
     * but real ones are a few paragraphs; 1 500 tokens ≈ 5-6 000 Danish
     * characters — generous without letting a runaway response spin.
     */
    static final int MAX_OUTPUT_TOKENS = 1500;

    /** Plan §P16: a temperature-capable model at ~0.7 for natural prose. */
    static final double TEMPERATURE = 0.7;

    /**
     * The draft model (plan §P16's {@code recruitment.ai.draft-model}).
     * Must be temperature-capable — the gpt-5 family rejects temperature
     * with HTTP 400 — so this defaults to a gpt-4o-family model. Override
     * per env with {@code DK_TRUSTWORKS_RECRUITMENT_AI_DRAFT_MODEL}.
     */
    @ConfigProperty(name = "dk.trustworks.recruitment.ai.draft-model", defaultValue = "gpt-4o-mini")
    String draftModel;

    @Inject
    OpenAIService openAIService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    RecruitmentEmailService emailService;

    @Inject
    RecruitmentAiVoiceCard voiceCard;

    /** The draft the endpoint answers with — same shape as a template render. */
    public record Draft(String subject, String body, RecruitmentEmailBodyFormat bodyFormat,
                        Set<String> unresolvedFields) {
    }

    /** Phase-1 output: every input the model round-trip and the append need. */
    private record PreparedDraft(String candidateUuid, String candidateFirstName,
                                 String templateUuid, String templateKey,
                                 String applicationUuid, String positionUuid,
                                 String positionTitle, String stage,
                                 String renderedSubject, String renderedBody,
                                 RecruitmentEmailBodyFormat bodyFormat,
                                 String voiceCard) {
    }

    /**
     * Generate one body draft. Must be called with no transaction active
     * (the endpoint is deliberately not {@code @Transactional} — §P9
     * security review M1).
     *
     * @param candidate     the addressed candidate (visibility already checked)
     * @param template      the template to personalise
     * @param application   optional application context, already validated
     *                      as belonging to the candidate (null = none)
     * @param instruction   optional recruiter instruction, already capped
     * @param actorUserUuid the recruiter — actor of the bookkeeping event
     * @throws IllegalStateException when OpenAI fails/refuses (empty output)
     *                               or a transaction is unexpectedly active
     */
    public Draft draft(RecruitmentCandidate candidate, RecruitmentEmailTemplate template,
                       RecruitmentApplication application, String instruction,
                       String actorUserUuid) {
        if (QuarkusTransaction.isActive()) {
            throw new IllegalStateException("draft must not be called inside a transaction");
        }
        // Phase 1 — read-only input gathering in its own completed tx.
        PreparedDraft prepared = QuarkusTransaction.requiringNew()
                .call(() -> prepare(candidate, template, application));
        // Phase 2 — the OpenAI round-trip, untransacted: no DB connection held.
        String body = callModel(prepared, instruction);
        // Phase 3 — the bookkeeping event in a fresh short tx.
        QuarkusTransaction.requiringNew()
                .run(() -> appendDraftEvent(prepared, instruction, body, actorUserUuid));
        return new Draft(prepared.renderedSubject(), body, prepared.bodyFormat(),
                RecruitmentEmailRenderer.tokensIn(body));
    }

    /** Phase 1: render the template and resolve position/stage context. */
    private PreparedDraft prepare(RecruitmentCandidate candidate, RecruitmentEmailTemplate template,
                                  RecruitmentApplication application) {
        RecruitmentPosition position = application == null ? null
                : RecruitmentPosition.findById(application.getPositionUuid());
        RecruitmentEmailBodyFormat bodyFormat = template.getBodyFormat() == null
                ? RecruitmentEmailBodyFormat.PLAIN : template.getBodyFormat();
        // Through the service, so the draft the model is handed already has
        // the house merge values resolved — an AI asked to personalise
        // "{{visiting_address}}" tends to invent an address.
        RecruitmentEmailRenderer.Rendered rendered =
                emailService.render(template, candidate, position);
        return new PreparedDraft(candidate.getUuid(), candidate.getFirstName(),
                template.getUuid(), template.getTemplateKey(),
                application == null ? null : application.getUuid(),
                position == null ? null : position.getUuid(),
                position == null ? null : position.getTitle(),
                application == null || application.getStage() == null
                        ? null : application.getStage().name(),
                rendered.subject(), rendered.body(), bodyFormat,
                // Read inside phase 1: the settings lookup is a DB read and
                // must not happen on the untransacted OpenAI leg.
                voiceCard.effectiveCard());
    }

    /** Phase 2: the text round-trip — network only, no DB access. */
    private String callModel(PreparedDraft prepared, String instruction) {
        boolean html = prepared.bodyFormat().isHtml();
        String raw = openAIService.generatePlainText(
                AiEmailComposerPrompts.systemPrompt(prepared.voiceCard(), html),
                AiEmailComposerPrompts.userPrompt(prepared.candidateFirstName(),
                        prepared.positionTitle(), prepared.stage(),
                        prepared.renderedBody(), instruction),
                draftModel, MAX_OUTPUT_TOKENS, TEMPERATURE, false);
        String body = html ? sanitizeHtmlBody(raw) : sanitizeBody(raw);
        if (body == null) {
            // Never log the prompt or output body — just the fact.
            throw new IllegalStateException(
                    "AI email draft returned no usable output for candidate "
                            + prepared.candidateUuid());
        }
        return body;
    }

    /**
     * Bookkeeping event (plan §P16): draft body — and the instruction and
     * subject, which both carry personal data — in {@code pii}; template
     * id and model in {@code payload}. The recruiter is the actor: the
     * draft was their explicit request.
     */
    private void appendDraftEvent(PreparedDraft prepared, String instruction, String body,
                                  String actorUserUuid) {
        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.AI_EMAIL_DRAFT_GENERATED)
                .candidate(prepared.candidateUuid())
                .application(prepared.applicationUuid())
                .position(prepared.positionUuid())
                .actorUser(actorUserUuid)
                .visibility(emailService.visibilityFor(prepared.candidateUuid()))
                .payload("template_uuid", prepared.templateUuid())
                .payload("template_key", prepared.templateKey())
                .payload("model", draftModel)
                .payload("prompt_version", AiEmailComposerPrompts.PROMPT_VERSION)
                // Structural, not personal: says whether the tone-of-voice
                // card was in force, so the spec's Danish spot review can
                // tell voice-guided drafts from opted-out ones.
                .payload("voice_card_applied", prepared.voiceCard() != null
                        && !prepared.voiceCard().isBlank())
                .pii("subject", prepared.renderedSubject())
                .pii("body", body);
        if (instruction != null && !instruction.isBlank()) {
            event.pii("instruction", instruction);
        }
        eventRecorder.record(event);
        log.infof("AI_EMAIL_DRAFT_GENERATED candidate=%s template=%s model=%s",
                prepared.candidateUuid(), prepared.templateKey(), draftModel);
    }

    /**
     * Draft-body sanitisation: normalise line endings, strip control
     * characters EXCEPT newline (an email body needs its paragraphs),
     * trim, cap at the send path's body limit. Blank ⇒ null (upstream
     * failure/refusal).
     */
    static String sanitizeBody(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cc}\\p{Cf}&&[^\\n]]", " ")
                .trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() > RecruitmentEmailService.BODY_MAX_LENGTH) {
            cleaned = cleaned.substring(0, RecruitmentEmailService.BODY_MAX_LENGTH).trim();
        }
        return cleaned;
    }

    /**
     * Draft-body sanitisation for an HTML template. Order matters:
     * <ol>
     *   <li>strip a wrapping markdown code fence — models emit
     *       <code>```html … ```</code> habitually regardless of instructions,
     *       and the fence would otherwise be sanitized into visible text;</li>
     *   <li>reduce to the allow-list, which also re-balances the fragment
     *       (jsoup closes an unterminated {@code <strong>} for us);</li>
     *   <li>check emptiness on the rendered text, not the string —
     *       {@code <p><br></p>} is a refusal dressed as content;</li>
     *   <li>cap length only AFTER sanitising, then re-clean, because cutting
     *       a raw string at N characters cuts mid-tag.</li>
     * </ol>
     * Blank ⇒ null (upstream failure/refusal), same contract as
     * {@link #sanitizeBody(String)}.
     */
    static String sanitizeHtmlBody(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = RecruitmentEmailHtmlSanitizer.clean(stripCodeFence(value));
        if (RecruitmentEmailHtmlSanitizer.isBlankHtml(cleaned)) {
            return null;
        }
        if (cleaned.length() > RecruitmentEmailService.BODY_MAX_LENGTH) {
            cleaned = RecruitmentEmailHtmlSanitizer.clean(
                    cleaned.substring(0, RecruitmentEmailService.BODY_MAX_LENGTH));
        }
        return cleaned;
    }

    /** Unwrap a single fenced code block, if the whole answer is one. */
    static String stripCodeFence(String value) {
        String trimmed = value.strip();
        if (!trimmed.startsWith("```")) {
            return value;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline < 0) {
            return value;
        }
        String inner = trimmed.substring(firstNewline + 1);
        int closing = inner.lastIndexOf("```");
        return closing < 0 ? inner : inner.substring(0, closing);
    }

    /** Test seam — the config value the service will send drafts with. */
    public String model() {
        return draftModel;
    }
}
