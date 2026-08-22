package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailBodyFormat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merge-field rendering for candidate emails (ATS plan §P15).
 * <p>
 * Vocabulary (documented with examples on the template dialog — extend the
 * frontend help text when adding a field here):
 * <ul>
 *   <li>{@code {{candidate_first_name}}} — e.g. "Anna"</li>
 *   <li>{@code {{candidate_last_name}}} — e.g. "Jensen"</li>
 *   <li>{@code {{candidate_full_name}}} — e.g. "Anna Jensen"</li>
 *   <li>{@code {{position_title}}} — e.g. "Senior Consultant"; empty when
 *       the email has no position context (e.g. unsolicited candidates)</li>
 *   <li>{@code {{consent_link}}} — the tokenized public consent-page URL
 *       (P19); resolves ONLY in the GDPR sweep's renewal send, which
 *       passes it via the extras overload — everywhere else the token
 *       stays visible as an unresolved placeholder (by design: a manual
 *       send of the renewal template cannot mint a token)</li>
 * </ul>
 * Unknown tokens are left untouched so they stay VISIBLE in the compose
 * preview and the review queue — a recruiter sees the problem instead of
 * the candidate receiving a silently half-rendered email.
 * <p>
 * <b>Two body formats (§P15 rich text).</b> A body is either legacy
 * {@code PLAIN} text or a sanitized {@code HTML} fragment, decided by the
 * stored {@code body_format} — never sniffed. Escaping differs accordingly,
 * and getting this right is the whole security story of the rich-text change:
 * <ul>
 *   <li>{@code PLAIN}: the merged body is escaped wholesale at send time, then
 *       newlines become {@code <br>}. Exactly the pre-rich-text behaviour.</li>
 *   <li>{@code HTML}: the body is markup <em>by design</em>, so it can no
 *       longer be escaped. Instead every merge <em>value</em> is escaped as it
 *       is substituted — {@code {{candidate_first_name}}} carries data typed
 *       into the public application form, and it is the one part of an HTML
 *       body an outsider controls. The result is sanitized again on the way
 *       out ({@link RecruitmentEmailHtmlSanitizer}).</li>
 * </ul>
 */
public final class RecruitmentEmailRenderer {

    private static final Pattern MERGE_FIELD = Pattern.compile("\\{\\{\\s*([a-z0-9_]+)\\s*}}");

    /**
     * A merge token whose braces survived but whose innards were broken up by
     * the rich editor — {@code {{candidate_<b>first</b>_name}}} after someone
     * bolded half of it, or a {@code &nbsp;} the browser inserted (which is
     * NOT {@code \s}). Left alone, {@link #MERGE_FIELD} simply would not match
     * and the candidate would receive the literal braces, with the
     * unresolved-token warning staying silent because it uses the same regex.
     * Healing the token is strictly better than surfacing a defect the author
     * cannot see the cause of.
     * <p>
     * Every quantifier here is possessive and bounded. A naive
     * {@code (?:a|b|c)*} recurses roughly one JVM frame per matched character
     * in {@code java.util.regex}, so a body well inside the 16 000-character
     * cap — {@code "{{" + "a".repeat(2000) + "}}"} — would throw
     * {@code StackOverflowError}. That is an {@code Error}, not an
     * {@code Exception}, so the reactor chassis' poison-skip would not catch
     * it and the candidate mailer's watermark would wedge: no acknowledgement,
     * stage or rejection email would ever be delivered again.
     */
    private static final Pattern SPLIT_MERGE_FIELD = Pattern.compile(
            "\\{\\{(?>[A-Za-z0-9_\\s\\u00a0]{1,80}+|<[^<>]{1,200}+>|&nbsp;){0,40}+}}");

    private static final Pattern HTML_TAG = Pattern.compile("<[^<>]*>");

    private RecruitmentEmailRenderer() {
    }

    /** A rendered subject/body pair plus any tokens that stayed unresolved. */
    public record Rendered(String subject, String body, Set<String> unresolvedFields) {
    }

    public static Rendered render(String subjectTemplate, String bodyTemplate,
                                  RecruitmentCandidate candidate, RecruitmentPosition position) {
        return render(subjectTemplate, bodyTemplate, candidate, position, Map.of(),
                RecruitmentEmailBodyFormat.PLAIN);
    }

    /**
     * Render with caller-supplied extra merge values on top of the standard
     * candidate/position vocabulary (P19: the sweep passes
     * {@code consent_link}). Extras win on key collision.
     */
    public static Rendered render(String subjectTemplate, String bodyTemplate,
                                  RecruitmentCandidate candidate, RecruitmentPosition position,
                                  Map<String, String> extras) {
        return render(subjectTemplate, bodyTemplate, candidate, position, extras,
                RecruitmentEmailBodyFormat.PLAIN);
    }

    /**
     * Render for a known body format. The subject is always plain text (a
     * subject line has no markup in any mail client), so only the body's
     * substitution is format-aware: on the HTML path merge values are escaped
     * as they land, and a link-shaped value becomes a real anchor.
     */
    public static Rendered render(String subjectTemplate, String bodyTemplate,
                                  RecruitmentCandidate candidate, RecruitmentPosition position,
                                  Map<String, String> extras,
                                  RecruitmentEmailBodyFormat bodyFormat) {
        Map<String, String> values = mergeValues(candidate, position);
        values.putAll(extras);
        Set<String> unresolved = new LinkedHashSet<>();
        String subject = substitute(subjectTemplate, values, unresolved,
                RecruitmentEmailBodyFormat.PLAIN);
        String body = substitute(bodyTemplate, values, unresolved,
                bodyFormat == null ? RecruitmentEmailBodyFormat.PLAIN : bodyFormat);
        return new Rendered(subject, body, unresolved);
    }

    private static Map<String, String> mergeValues(RecruitmentCandidate candidate,
                                                   RecruitmentPosition position) {
        String first = candidate == null || candidate.getFirstName() == null ? "" : candidate.getFirstName().trim();
        String last = candidate == null || candidate.getLastName() == null ? "" : candidate.getLastName().trim();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("candidate_first_name", first);
        values.put("candidate_last_name", last);
        values.put("candidate_full_name", (first + " " + last).trim());
        values.put("position_title",
                position == null || position.getTitle() == null ? "" : position.getTitle().trim());
        return values;
    }

    private static String substitute(String template, Map<String, String> values,
                                     Set<String> unresolved,
                                     RecruitmentEmailBodyFormat format) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        boolean html = format != null && format.isHtml();
        String source = html ? healSplitTokens(template) : template;
        Matcher matcher = MERGE_FIELD.matcher(source);
        StringBuilder sb = new StringBuilder(source.length() + 32);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = values.get(key);
            if (value == null) {
                unresolved.add(key);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                // The escape that used to be applied to the whole body at send
                // time. On the HTML path it MUST happen here instead: the body
                // is markup we mean to keep, the value is data we never do.
                matcher.appendReplacement(sb,
                        Matcher.quoteReplacement(html ? renderValueAsHtml(key, value) : value));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Escape a merge value for an HTML body. A value the vocabulary declares to
     * be a URL ({@code *_link}) additionally becomes a clickable anchor —
     * {@code {{consent_link}}} in an HTML body would otherwise arrive as dead
     * text and the GDPR consent renewal would quietly stop working.
     * <p>
     * Newlines in the value become {@code <br>}: an HTML body renders a bare
     * {@code \n} as a single space, so a multi-line value — Method B's
     * {@code {{options_list}}}, a numbered list of interview times — would
     * otherwise arrive as one run-on line. The line structure is part of the
     * value; the conversion happens after escaping, so the {@code <br>} is the
     * one piece of markup a value may carry.
     */
    private static String renderValueAsHtml(String key, String value) {
        String escaped = RecruitmentEmailHtmlSanitizer.escapeHtml(value);
        if (key.endsWith("_link") && (value.startsWith("https://") || value.startsWith("http://"))) {
            return "<a href=\"" + escaped + "\">" + escaped + "</a>";
        }
        return escaped.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br>");
    }

    /**
     * Remove markup and non-breaking spaces from inside otherwise well-formed
     * merge tokens, so a token the author accidentally formatted across still
     * resolves. Only the span between the braces is touched — markup elsewhere
     * in the body is the author's and is left exactly as written.
     */
    static String healSplitTokens(String html) {
        if (html == null || html.indexOf('{') < 0) {
            return html;
        }
        Matcher matcher = SPLIT_MERGE_FIELD.matcher(html);
        StringBuilder sb = new StringBuilder(html.length());
        while (matcher.find()) {
            String span = matcher.group();
            String healed = HTML_TAG.matcher(span).replaceAll("")
                    .replace("&nbsp;", " ")
                    .replace('\u00a0', ' ');
            // Two conditions, both required. The tags inside the span must be
            // self-contained, or removing them unbalances the surrounding
            // document — "<b>Hej {{candidate</b>_navn}}" would lose the </b>
            // and jsoup would re-balance by bolding the rest of the paragraph.
            // And the result must genuinely be a merge token, so stray braces
            // are left exactly as the author wrote them.
            boolean safe = tagsAreSelfContained(span) && MERGE_FIELD.matcher(healed).matches();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(safe ? healed : span));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * All merge-field-shaped tokens ({@code {{token}}}) still present in a
     * text — the compose dialog's unresolved-token warning for content
     * that was not produced by {@link #render} (the P16 AI draft, which is
     * told to keep placeholders verbatim).
     */
    public static Set<String> tokensIn(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        Matcher matcher = MERGE_FIELD.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group(1));
        }
        return tokens;
    }

    /**
     * Unresolved merge tokens that the vocabulary declares to be URLs
     * ({@code *_link}) — the send-time gate for a finished email.
     * <p>
     * Every other unresolved token is cosmetic: the candidate reads
     * {@code {{position_title}}} and understands a mail-merge went wrong. A
     * link token is not cosmetic. {@code {{consent_link}}} resolves ONLY in
     * the GDPR sweep's renewal send (it is the one caller that can mint a
     * token), so a hand-composed send of that template mails a GDPR consent
     * link that does not exist — and the candidate is deleted at the
     * retention deadline for not clicking something unclickable. Refusing
     * the send is the only safe answer; a warning was not enough.
     * <p>
     * The body is healed first on the HTML path, exactly as
     * {@link #substitute} would: a token the rich editor split across markup
     * is still a broken link, and must not slip past the gate because a
     * {@code <b>} landed in the middle of it.
     *
     * @param format how {@code body} is to be read; null is treated as PLAIN
     * @return the offending token names, in the order encountered; empty when
     *         the email is safe to send
     */
    public static Set<String> unresolvedLinkTokens(String subject, String body,
                                                   RecruitmentEmailBodyFormat format) {
        boolean html = format != null && format.isHtml();
        Set<String> tokens = new LinkedHashSet<>(tokensIn(subject));
        tokens.addAll(tokensIn(html ? healSplitTokens(body) : body));
        tokens.removeIf(token -> !token.endsWith("_link"));
        return tokens;
    }

    /**
     * True when every tag inside the span opens and closes within it, so
     * deleting them all leaves the surrounding document's structure untouched.
     * A void element ({@code <br>}) counts as unbalanced — conservative, and
     * no merge token has a reason to contain one.
     */
    private static boolean tagsAreSelfContained(String span) {
        Deque<String> open = new ArrayDeque<>();
        Matcher tags = HTML_TAG.matcher(span);
        while (tags.find()) {
            String tag = tags.group();
            String name = tag.replaceAll("^</?\\s*", "").replaceAll("[\\s/>].*$", "")
                    .toLowerCase();
            if (tag.startsWith("</")) {
                if (open.isEmpty() || !open.pop().equals(name)) {
                    return false;
                }
            } else {
                open.push(name);
            }
        }
        return open.isEmpty();
    }

    /**
     * Plain text → mail-client-safe HTML: escape, then newlines to
     * {@code <br>}, wrapped in a minimal container. Danish characters pass
     * through untouched (UTF-8 end to end — only markup-significant
     * characters are entity-escaped).
     */
    public static String toHtml(String plainText) {
        String escaped = RecruitmentEmailHtmlSanitizer.escapeHtml(plainText == null ? "" : plainText);
        return wrap(escaped.replace("\r\n", "\n").replace("\n", "<br>\n"));
    }

    /**
     * The single conversion point at send time, for either format. This is the
     * last thing that touches a body before it becomes a {@code mail} row, so
     * the HTML path re-sanitizes here rather than trusting that whoever stored
     * the body did — a template can be written by one release and sent by the
     * next, and the review queue holds text a recruiter edited by hand.
     *
     * @param body   the merge-rendered body
     * @param format how {@code body} is to be read; null is treated as PLAIN
     */
    public static String toHtml(String body, RecruitmentEmailBodyFormat format) {
        if (format == null || !format.isHtml()) {
            return toHtml(body);
        }
        return wrap(RecruitmentEmailHtmlSanitizer.clean(body));
    }

    /**
     * The minimal container every candidate email has carried since P15.
     * {@code sans-serif} because mail clients otherwise default body text to a
     * serif face, and {@code white-space: normal} to stop Outlook inheriting a
     * {@code pre} context from a forwarded thread.
     */
    private static String wrap(String htmlFragment) {
        return "<div style=\"font-family: sans-serif; white-space: normal;\">"
                + htmlFragment
                + "</div>";
    }
}
