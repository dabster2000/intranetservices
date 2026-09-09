package dk.trustworks.intranet.recruitmentservice.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks compensation <em>amounts</em> inside free text for viewers outside
 * the comp tier.
 * <p>
 * <b>Why this exists.</b> {@code RecruitmentFactVocabulary.isCompScoped}
 * gates the structured facts — a {@code NOTE_ADDED} whose
 * {@code payload.field} is {@code SALARY_EXPECTATION},
 * {@code SALARY_COMPONENTS} or {@code CURRENT_PACKAGE}. That predicate is
 * keyed on the field, so a note carrying {@code field: null} was never
 * redacted however plainly it stated the number. On 2026-09-09 production
 * held, on candidates reachable by a live {@code RECRUITMENT_ASSISTANT},
 * an untagged note reading "hendes lønforventning var 70.000 + pension"
 * sitting one row from the tagged {@code SALARY_EXPECTATION} note that the
 * timeline correctly redacted — the same figure, twice, one of them in the
 * clear. The Airtable migration imported more of the same
 * ("Hans lønpakke lige nu er 85000 + 10% pension"), and
 * {@code AI_SUGGESTIONS_GENERATED} carried
 * {@code "SALARY_EXPECTATION=70.000kr om måneden"} in its pii while escaping
 * the gate purely because it is not a {@code NOTE_ADDED}.
 * <p>
 * <b>The rule.</b> A text that mentions compensation vocabulary has every
 * money-shaped token in it masked. Deliberately a two-factor test: the
 * vocabulary alone would mask nothing useful, and money shapes alone would
 * mask every date, phone number and headcount in the module. Requiring both
 * keeps the prose readable — the assistant still sees <em>that</em> a salary
 * conversation happened, which is what their job needs — while the figure
 * goes. That was the 2026-09-09 product decision: mask the amounts, keep the
 * prose.
 * <p>
 * <b>Fail closed, whole-text scope.</b> The vocabulary hit does not have to
 * be near the number. A long Airtable "ALL DATA" dump that mentions pension
 * once has all of its numbers masked, including dates the assistant might
 * have liked. That is the intended trade: a proximity window is exactly what
 * an author routes around by writing the figure on its own line.
 * <p>
 * <b>What this is not.</b> A detector is not a boundary. It is the second
 * line behind {@code RecruitmentVisibility.isCompTierFor}, which remains the
 * authority for structured facts and for the offer dossier. Anything that
 * must never be seen belongs in a typed field the gate can key on, not in
 * free text that this class has to guess about.
 */
public final class CompensationTextRedactor {

    private CompensationTextRedactor() {
    }

    /** Danish letters count as letters — {@code \w} does not include æøå. */
    private static final String LETTER = "a-zA-ZæøåÆØÅ";

    /**
     * Compensation vocabulary, Danish and English.
     * <p>
     * The lookarounds are letter-only rather than {@code \b} on purpose:
     * {@code \bsalary\b} does not match inside {@code BASE_SALARY} or
     * {@code SALARY_EXPECTATION} because {@code _} is a word character, and
     * those are exactly the shapes the dossier placeholders and the AI
     * suggestion strings use.
     * <p>
     * {@code løn} is matched as a bare substring, with no boundary at all:
     * Danish compounds it on both sides ({@code lønforventning} but also
     * {@code månedsløn}, {@code grundløn}, {@code årsløn}), so anchoring it
     * to a word start misses half of them. The ø-less {@code lon} spelling
     * cannot be treated that way — it is a substring of {@code kolonne},
     * {@code salon} and {@code colon} — so that branch stays word-initial.
     * <p>
     * The currency and earning branches ({@code kr}, {@code kroner},
     * {@code dkk}, {@code mio}, {@code tjene}) are what catch a figure stated
     * with no compensation noun anywhere near it. Production held exactly
     * that shape — "Hun vil gerne tjene 70.000kr om måneden" — and an earlier
     * noun-only vocabulary let it through.
     * <p>
     * Bare {@code forventning} / {@code expectation} are deliberately absent:
     * they are ordinary words in this module ("hun forventer at starte 1.
     * oktober") and {@code lønforventning} is already covered. {@code pay}
     * and {@code comp} are absent for the same reason.
     */
    private static final Pattern COMPENSATION_VOCABULARY = Pattern.compile(
            "løn"                                        // substring: lønforventning AND månedsløn
                    + "|(?<![" + LETTER + "])("
                    + "lon[" + LETTER + "]*"             // word-initial only: not kolonne/salon
                    + "|gage|honorar|vederlag"
                    + "|salary|salaries|salaried|compensation|remuneration|wage|wages"
                    + "|pension[" + LETTER + "]*"
                    + "|bonus|bonusser|provision|incentive"
                    + "|package|pakke|firmabil"
                    + "|kr|kr\\.|dkk|kroner|mio|mia"     // a figure with a currency unit
                    + "|tjene|tjener|tjent|earn|earns|earned|earning"
                    + ")(?![" + LETTER + "])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Money shapes, Danish conventions.
     * <p>
     * Tuned against real production text to leave the surrounding facts
     * intact. It must NOT match {@code 2026-08-11}, {@code 17.6.},
     * {@code 28/5}, {@code 13:44}, {@code 5%}, {@code 5-10%}, {@code 2x} or
     * the Airtable record ids ({@code recBMZggwGWgRZ69E}) that share these
     * notes — hence the 5-digit floor on bare integers (a bare year is four)
     * and the exact-three-digit grouping.
     */
    private static final Pattern MONEY = Pattern.compile(
            "(?<![\\d.,])("
                    + "\\d{1,3}(?:[.\\u00A0 ]\\d{3})+(?:[.,]\\d{1,2})?"  // 70.000  1.500.000  85 000
                    + "|\\d{5,8}(?:[.,]\\d{1,2})?"                        // 85000  115000  85000.00
                    + "|\\d{2,4}(?:[.,]\\d{1,2})?\\s?[kK](?![" + LETTER + "\\d])"  // 63k  80 k
                    + "|\\d{1,3}(?:[.,]\\d{1,2})?\\s?mio\\.?"             // 1,1 mio
                    + ")(?![\\d])");

    /** What replaces a masked amount. Language-neutral and obviously a hole. */
    public static final String MASK = "●●●";

    /** Whether the text mentions compensation at all — the first factor. */
    public static boolean mentionsCompensation(String text) {
        return text != null && COMPENSATION_VOCABULARY.matcher(text).find();
    }

    /**
     * Whether {@link #redact} would change this text — i.e. it mentions
     * compensation AND carries at least one money-shaped token. Callers use
     * this to set the "amounts withheld" marker on a response without
     * comparing strings.
     */
    public static boolean carriesCompensationAmount(String text) {
        return mentionsCompensation(text) && MONEY.matcher(text).find();
    }

    /**
     * @return the text with every money-shaped token masked when it mentions
     *         compensation; the same instance otherwise (so a non-matching
     *         payload is not needlessly rewritten).
     */
    public static String redact(String text) {
        if (!mentionsCompensation(text)) {
            return text;
        }
        Matcher matcher = MONEY.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        do {
            out.append(text, cursor, matcher.start()).append(MASK);
            cursor = matcher.end();
        } while (matcher.find());
        return out.append(text, cursor, text.length()).toString();
    }

    /**
     * Recursively mask every string in a parsed pii/payload document.
     * <p>
     * Applied to the whole document rather than a known text key because the
     * shapes differ per event type and a new one must not silently arrive
     * unfiltered: notes carry {@code text}, AI generations carry
     * {@code suggestions} and {@code bullets}, scorecards carry {@code notes},
     * e-mails carry {@code body}. Keys are structural and never masked.
     *
     * @return a masked copy, or the same instance when nothing matched
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> redactDocument(Map<String, Object> document) {
        if (document == null || document.isEmpty()) {
            return document;
        }
        Map<String, Object> copy = null;
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            Object masked = redactValue(entry.getValue());
            if (masked != entry.getValue()) {
                if (copy == null) {
                    copy = new LinkedHashMap<>(document);
                }
                copy.put(entry.getKey(), masked);
            }
        }
        return copy == null ? document : copy;
    }

    @SuppressWarnings("unchecked")
    private static Object redactValue(Object value) {
        if (value instanceof String text) {
            return redact(text);
        }
        if (value instanceof Map<?, ?> nested) {
            return redactDocument((Map<String, Object>) nested);
        }
        if (value instanceof List<?> items) {
            List<Object> masked = null;
            for (int i = 0; i < items.size(); i++) {
                Object element = redactValue(items.get(i));
                if (element != items.get(i)) {
                    if (masked == null) {
                        masked = new ArrayList<>(items);
                    }
                    masked.set(i, element);
                }
            }
            return masked == null ? value : masked;
        }
        return value;
    }

    /** Whether any string anywhere in the document would be masked. */
    @SuppressWarnings("unchecked")
    public static boolean documentCarriesCompensationAmount(Map<String, Object> document) {
        if (document == null || document.isEmpty()) {
            return false;
        }
        return document.values().stream().anyMatch(CompensationTextRedactor::valueCarriesAmount);
    }

    @SuppressWarnings("unchecked")
    private static boolean valueCarriesAmount(Object value) {
        if (value instanceof String text) {
            return carriesCompensationAmount(text);
        }
        if (value instanceof Map<?, ?> nested) {
            return documentCarriesCompensationAmount((Map<String, Object>) nested);
        }
        if (value instanceof List<?> items) {
            return items.stream().anyMatch(CompensationTextRedactor::valueCarriesAmount);
        }
        return false;
    }
}
