package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.recruitmentservice.ai.AiEmailComposerPrompts;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * The Trustworks tone-of-voice card the AI email composer writes by
 * (AI spec §5.4).
 *
 * <p>The card is a short Danish distillation of the brand voice guide's
 * three principles — <em>Menneskelig</em>, <em>Klar</em>, <em>Ordentlig</em>
 * — plus the terminology the guide rules out. It is deliberately Danish:
 * candidate mail is Danish (main spec's language rule), and native-language
 * style rules steer native-language prose far better than a translated
 * ruleset does.
 *
 * <h3>Where the value lives</h3>
 * One {@code app_settings} row, {@code recruitment.ai.email-composer.voice-card}
 * in category {@code recruitment} — the same storage and the same
 * settings page as {@link RecruitmentEmailService#SETTING_REPLY_TO_FALLBACK}.
 * There is no seed migration on purpose: {@link #DEFAULT_CARD} is the
 * built-in default and the row is written only when someone edits the text
 * on {@code /recruitment/settings}. That keeps the feature correct in every
 * environment (a missing row is not a broken feature) and survives staging's
 * nightly prod→staging {@code app_settings} copy, which would strip a
 * staging-only seed.
 *
 * <h3>Three states, on purpose</h3>
 * <ul>
 *   <li><b>Row absent</b> — never configured: {@link #DEFAULT_CARD} applies.</li>
 *   <li><b>Row with text</b> — the configured card applies.</li>
 *   <li><b>Row with an empty value</b> — an explicit opt-out: the composer
 *       runs with no voice guidance at all, exactly as it did before this
 *       feature. This mirrors the blank-Reply-To opt-out next to it.</li>
 * </ul>
 *
 * <h3>Security</h3>
 * The card is written into the composer's <em>system</em> prompt, so the
 * write path is recruiter-tier only (ADMIN/HR/CXO + {@code recruitment:write}
 * at the resource, {@code requireRoles} at the BFF) — the same tier that
 * already authors the email templates whose bodies reach the same model.
 * {@link #sanitize} strips control characters and the composer's data
 * delimiters (a card must never be able to forge the boundary that keeps
 * candidate-supplied material quarantined as data) and caps the length so a
 * pasted essay cannot inflate every draft call.
 */
@ApplicationScoped
public class RecruitmentAiVoiceCard {

    /** The single {@code app_settings} key holding the card. */
    public static final String SETTING_KEY = "recruitment.ai.email-composer.voice-card";

    /** Same category as the sibling recruitment settings on the page. */
    private static final String SETTING_CATEGORY = "recruitment";

    /**
     * Hard cap. The default card is ~1 400 characters; 4 000 leaves room to
     * elaborate without letting the system prompt outgrow the template it is
     * supposed to be styling.
     */
    public static final int MAX_LENGTH = 4_000;

    /**
     * The built-in card — a Danish distillation of the Trustworks brand voice
     * guide (v1.0, 2026-04-08), narrowed to what actually shows up in a
     * candidate email. Editable on {@code /recruitment/settings}; this
     * constant is what a never-configured installation writes by.
     */
    public static final String DEFAULT_CARD = """
            Trustworks' tone of voice hviler på tre principper:

            MENNESKELIG
            - Skriv som ét menneske til ét andet. Brug "vi" og "du".
            - Vær nærværende, ærlig og konkret. Aktive verber, ikke passive vendinger.
            - Hverdagssprog — som du ville tale til en kollega, du har respekt for.

            KLAR
            - Det vigtigste først. Ét emne pr. sætning, ét budskab pr. afsnit.
            - Hvert ord skal gøre sig fortjent. Færre ord er bedre end flere.
            - Klarhed er respekt for modtagerens tid.

            ORDENTLIG
            - Selvsikker, aldrig nedladende. Varm, aldrig anmassende.
            - Sig tingene som de er — også når de er ubehagelige. Lov aldrig mere, end vi kan holde.
            - Ingen selvpromovering og ingen superlativer; vi lader resultaterne tale.

            UNDGÅ disse ord og vendinger:
            "holistisk", "synergi", "value proposition", "best practice", "robust",
            "optimere", "leverage", "empower", "transformation", "sømløs",
            "stakeholder-alignment", "markedsledende", "world-class",
            "vi er stolte af", "hos Trustworks tror vi på …".
            Undgå også garantier og absolutter ("altid", "aldrig", "garanterer")
            og fyldord ("i den forbindelse", "det er værd at bemærke", "snarest muligt").

            SKRIV I STEDET:
            - Konkret hvad der sker, hvornår, og hvad vi har brug for.
            - "Vi vender tilbage senest på fredag" frem for "vi vender tilbage snarest muligt".
            - En afvisning skal være kort, respektfuld og uden falsk trøst.

            Prøven inden afsendelse: ville du sige det sådan til en kollega, du har respekt for?
            """;

    @Inject
    AppSettingService appSettingService;

    /**
     * The card the composer should write by right now: the stored value when
     * one exists, otherwise {@link #DEFAULT_CARD}.
     *
     * @return the card, or {@code null} when the stored value is blank — the
     *         explicit "no voice guidance" opt-out
     */
    public String effectiveCard() {
        Optional<String> stored = appSettingService.findByKey(SETTING_KEY)
                .map(AppSetting::getSettingValue);
        if (stored.isEmpty()) {
            return defaultCard();
        }
        String value = stored.get().trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * The built-in card, trimmed the same way a stored one is — so the
     * settings page's "restore the default text" produces a value that
     * compares equal to what the server would hand back, and the Save button
     * does not light up on a card nobody edited.
     */
    public static String defaultCard() {
        return DEFAULT_CARD.trim();
    }

    /**
     * What the settings page shows in its editor: the same text
     * {@link #effectiveCard()} resolves to, with the opt-out rendered as an
     * empty string rather than null.
     */
    public String editableCard() {
        String effective = effectiveCard();
        return effective == null ? "" : effective;
    }

    /** True while no row exists — the page can say "this is the built-in text". */
    public boolean isDefault() {
        return appSettingService.findByKey(SETTING_KEY).isEmpty();
    }

    /**
     * Persist a new card. An empty value is legal and means "compose without
     * voice guidance".
     *
     * @throws BusinessRuleViolation when the card exceeds {@link #MAX_LENGTH}
     */
    public void update(String value, String actorUserUuid) {
        String sanitized = sanitize(value);
        if (sanitized.length() > MAX_LENGTH) {
            throw new BusinessRuleViolation(
                    "The tone-of-voice card must be at most " + MAX_LENGTH + " characters");
        }
        appSettingService.saveSetting(SETTING_KEY, sanitized, SETTING_CATEGORY, actorUserUuid);
    }

    /**
     * Normalise line endings, drop control characters except newline, and
     * remove the composer's data delimiters so a card can never forge the
     * boundary that keeps candidate-supplied material quarantined as data.
     */
    public static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", "\n").replace('\r', '\n')
                .replace(AiEmailComposerPrompts.DATA_START, " ")
                .replace(AiEmailComposerPrompts.DATA_END, " ")
                .replaceAll("[\\p{Cc}\\p{Cf}&&[^\\n]]", " ")
                .trim();
    }
}
