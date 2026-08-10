package dk.trustworks.intranet.recruitmentservice.services;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/**
 * The single allow-list for rich-text candidate-email bodies (ATS plan §P15,
 * rich text).
 * <p>
 * <b>Why an allow-list and not escaping.</b> A candidate email is
 * attacker-adjacent content: {@code {{candidate_first_name}}} merges a value
 * straight from the public application form, and the body is stored, shown
 * to recruiters in-app, exported in DSAR deliverables and finally rendered by
 * a mail client. jsoup parses and rebuilds the document from the allow-list
 * rather than filtering strings, so anything not named here — {@code <script>},
 * {@code <style>}, {@code <iframe>}, {@code <img>}, event handlers,
 * {@code javascript:} URLs, malformed and unbalanced markup — cannot survive,
 * whatever shape it arrives in.
 * <p>
 * <b>Why this particular list.</b> It is the intersection of "useful in a
 * recruitment email" and "renders the same in Outlook desktop, Gmail web and
 * Gmail iOS". Deliberately absent:
 * <ul>
 *   <li>{@code style}, {@code class}, {@code id} — Gmail strips {@code <style>}
 *       blocks and {@code class}/{@code id} have nothing to bind to in a mail
 *       client, so they are dead weight that only widens the attack surface.</li>
 *   <li>{@code <img>} — a remote image in a candidate email is an unreviewed
 *       tracking pixel with GDPR consequences, and needs hosting we do not have.</li>
 *   <li>{@code <table>} — Outlook's Word rendering engine and webmail disagree
 *       enough that a recruiter would be authoring blind.</li>
 * </ul>
 * <b>No enforced attributes.</b> Nothing is added to the markup on the way
 * through (no {@code rel=nofollow}, no {@code target}). That keeps the
 * sanitizer purely subtractive, which is what lets the frontend DOMPurify
 * configuration and this one agree byte-for-byte on the same input — so a body
 * the editor accepted can never be rejected or silently rewritten here.
 *
 * @see dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailBodyFormat
 */
public final class RecruitmentEmailHtmlSanitizer {

    /**
     * Sentinel marking a line break while {@link #toPlainText(String)} walks the
     * document. A printable BMP character rather than a control code, because
     * the HTML tokenizer rewrites NUL to U+FFFD. Any occurrence already present
     * in the input is removed before the walk, so inside it only ever means
     * "line break here".
     */
    private static final String BREAK_MARKER = "␞";

    /** Elements that end a visual line when flattening to plain text. */
    private static final String BLOCK_SELECTOR = "p, div, li, blockquote";

    /** What JavaScript's {@code String.trim()} strips — wider than Java's. */
    private static final String JS_TRIM_CLASS = "[\\s\\u00a0\\ufeff]";

    private static final Safelist SAFELIST = new Safelist()
            .addTags("a", "b", "blockquote", "br", "div", "em", "i", "li",
                    "ol", "p", "span", "strong", "u", "ul")
            .addAttributes("a", "href", "title")
            .addProtocols("a", "href", "http", "https", "mailto");

    private RecruitmentEmailHtmlSanitizer() {
    }

    /** Non-pretty output: jsoup must not reflow the author's markup. */
    private static Document.OutputSettings outputSettings() {
        return new Document.OutputSettings().prettyPrint(false);
    }

    /**
     * Reduce an untrusted HTML fragment to the allow-list. Never trusts the
     * client: this runs on every write AND again on the send path, because a
     * body can be stored by one release and sent by another.
     *
     * @param html raw fragment; null/blank yields ""
     * @return a balanced, allow-listed fragment safe to store, render and mail
     */
    public static String clean(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        // Control characters first: they are invisible in every review surface
        // but survive into the mail, and they would collide with BREAK_MARKER.
        String stripped = html.replace("\r\n", "\n")
                .replaceAll("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]", "");
        // Empty baseUri with preserveRelativeLinks off (jsoup's default) means a
        // relative href cannot be resolved and is dropped — only the three
        // allow-listed absolute protocols get through.
        return Jsoup.clean(stripped, "", SAFELIST, outputSettings());
    }

    /**
     * True when a fragment carries no readable content — {@code <p><br></p>} is
     * what an "empty" contentEditable serialises to, and {@code String.isBlank()}
     * happily calls that non-empty. Every required-body check must go through
     * here or an empty email ships.
     */
    public static boolean isBlankHtml(String html) {
        if (html == null || html.isBlank()) {
            return true;
        }
        return Jsoup.parseBodyFragment(html).body().text().isBlank();
    }

    /**
     * Flatten a fragment to readable plain text, preserving line structure.
     * Used wherever a body must be read rather than rendered — GDPR/DSAR
     * deliverables, the AI composer's input, log-adjacent surfaces — so those
     * never degrade into tag soup.
     */
    public static String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parseBodyFragment(html.replace(BREAK_MARKER, ""));
        doc.outputSettings(outputSettings());
        doc.select("br").after(BREAK_MARKER);
        doc.select(BLOCK_SELECTOR).after(BREAK_MARKER + BREAK_MARKER);
        // text() collapses runs of whitespace, so the markers are the only
        // surviving line structure — which is exactly what we want to restore.
        String flattened = doc.body().text();
        StringBuilder sb = new StringBuilder(flattened.length());
        for (String part : flattened.split(BREAK_MARKER, -1)) {
            sb.append(part.strip()).append('\n');
        }
        return sb.toString()
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    /**
     * Plain text to an equivalent HTML fragment: blank-line-separated blocks
     * become paragraphs, single newlines become {@code <br>}. This is the
     * up-conversion used when a legacy {@code PLAIN} template is opened in the
     * rich editor, and it must stay behaviourally identical to the frontend's
     * {@code plainToHtml} so the seeded editor content round-trips unchanged.
     *
     * @return a fragment; {@code <p></p>} for null/blank input
     */
    public static String plainToHtml(String plainText) {
        // Java's isBlank()/strip() deliberately treat U+00A0 and U+FEFF as
        // non-whitespace while JavaScript's trim() removes them. The frontend
        // runs the same conversion to seed its editor, so this has to match it
        // or a template round-trips one blank paragraph shorter than the
        // compose dialog showed.
        if (plainText == null || plainText.replaceAll(JS_TRIM_CLASS, "").isEmpty()) {
            return "<p></p>";
        }
        String normalized = plainText.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder(normalized.length() + 32);
        for (String paragraph : normalized.split("\n{2,}")) {
            String trimmed = paragraph
                    .replaceAll("^" + JS_TRIM_CLASS + "+", "")
                    .replaceAll(JS_TRIM_CLASS + "+$", "");
            if (trimmed.isEmpty()) {
                continue;
            }
            sb.append("<p>")
                    .append(escapeHtml(trimmed).replace("\n", "<br>"))
                    .append("</p>");
        }
        return sb.isEmpty() ? "<p></p>" : sb.toString();
    }

    /**
     * Escape the five markup-significant characters. Danish characters pass
     * through untouched — UTF-8 end to end, only markup is entity-encoded.
     * <p>
     * This is the escape applied to merge <em>values</em> on the HTML path
     * (a candidate-supplied name is data, never markup), and to the whole body
     * on the legacy {@code PLAIN} path.
     */
    public static String escapeHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
