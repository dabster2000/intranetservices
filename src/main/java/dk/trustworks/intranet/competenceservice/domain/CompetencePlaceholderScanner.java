package dk.trustworks.intranet.competenceservice.domain;

import dk.trustworks.intranet.competenceservice.content.CompetenceContent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds unresolved authoring placeholders before a version can go live.
 *
 * <p>The 2026-08 content ships with 24 callouts reading
 * {@code [Udfyldes af Trustworks · TW-nn]} — questions only Trustworks can answer — plus
 * four video slots reading {@code [Udfyldes + godkendes inden ibrugtagning]}. Publishing
 * any of that to an auditor would undermine the very claim the module exists to support,
 * so publish is blocked unless the caller explicitly acknowledges (spec §9.4).
 *
 * <p>The scan is for the literal {@code [Udfyldes}, not for the TW pattern: the four video
 * notes carry no TW ref, and they matter just as much.
 */
public final class CompetencePlaceholderScanner {

    /** The literal that marks any unresolved authoring placeholder. */
    public static final String MARKER = "[Udfyldes";

    private static final Pattern TW_REF = Pattern.compile("TW-\\d+");

    private CompetencePlaceholderScanner() {
    }

    /**
     * @param screenIndex 1-based, for the editor's "go to screen" affordance
     * @param screenTitle so the author can find it without counting
     * @param blockType   {@code callout} or {@code video}
     * @param ref         the TW reference, or {@code null} for the video slots
     * @param excerpt     a short, trimmed sample of the offending text
     */
    public record Finding(int screenIndex, String screenTitle, String blockType,
                          String ref, String excerpt) {
    }

    public static List<Finding> scan(CompetenceContent.CoursePayload payload) {
        List<Finding> findings = new ArrayList<>();
        if (payload == null) {
            return findings;
        }
        int screenIndex = 0;
        for (CompetenceContent.Screen screen : payload.screens()) {
            screenIndex++;
            for (CompetenceContent.Block block : screen.blocks()) {
                for (String text : textsOf(block)) {
                    if (text == null || !text.contains(MARKER)) {
                        continue;
                    }
                    Matcher m = TW_REF.matcher(text);
                    findings.add(new Finding(
                            screenIndex,
                            screen.title(),
                            block.type(),
                            m.find() ? m.group() : null,
                            excerpt(text)));
                }
            }
        }
        return findings;
    }

    /** Distinct TW references across the findings, in first-seen order. */
    public static Set<String> distinctRefs(List<Finding> findings) {
        Set<String> refs = new LinkedHashSet<>();
        for (Finding f : findings) {
            if (f.ref() != null) {
                refs.add(f.ref());
            }
        }
        return refs;
    }

    /** Every string a block can carry that an author might leave a placeholder in. */
    private static List<String> textsOf(CompetenceContent.Block block) {
        List<String> out = new ArrayList<>(4);
        out.add(block.text());
        out.add(block.note());
        out.add(block.code());
        if (block.items() != null) {
            out.addAll(block.items());
        }
        return out;
    }

    private static String excerpt(String text) {
        int at = text.indexOf(MARKER);
        int from = Math.max(0, at);
        int to = Math.min(text.length(), from + 120);
        String slice = text.substring(from, to).trim();
        return to < text.length() ? slice + "…" : slice;
    }
}
