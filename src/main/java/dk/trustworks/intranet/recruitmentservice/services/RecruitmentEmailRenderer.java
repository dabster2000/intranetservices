package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;

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
 * </ul>
 * Unknown tokens are left untouched so they stay VISIBLE in the compose
 * preview and the review queue — a recruiter sees the problem instead of
 * the candidate receiving a silently half-rendered email.
 * <p>
 * Templates and merged values are plain text. {@link #toHtml(String)} is the
 * single conversion point at send time: HTML-escape everything, then
 * newlines → {@code <br>} — template or candidate data can never inject
 * markup into the mail client.
 */
public final class RecruitmentEmailRenderer {

    private static final Pattern MERGE_FIELD = Pattern.compile("\\{\\{\\s*([a-z0-9_]+)\\s*}}");

    private RecruitmentEmailRenderer() {
    }

    /** A rendered subject/body pair plus any tokens that stayed unresolved. */
    public record Rendered(String subject, String body, Set<String> unresolvedFields) {
    }

    public static Rendered render(String subjectTemplate, String bodyTemplate,
                                  RecruitmentCandidate candidate, RecruitmentPosition position) {
        Map<String, String> values = mergeValues(candidate, position);
        Set<String> unresolved = new LinkedHashSet<>();
        String subject = substitute(subjectTemplate, values, unresolved);
        String body = substitute(bodyTemplate, values, unresolved);
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
                                     Set<String> unresolved) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher matcher = MERGE_FIELD.matcher(template);
        StringBuilder sb = new StringBuilder(template.length() + 32);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = values.get(key);
            if (value == null) {
                unresolved.add(key);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Plain text → mail-client-safe HTML: escape, then newlines to
     * {@code <br>}, wrapped in a minimal container. Danish characters pass
     * through untouched (UTF-8 end to end — only markup-significant
     * characters are entity-escaped).
     */
    public static String toHtml(String plainText) {
        String escaped = escapeHtml(plainText == null ? "" : plainText);
        return "<div style=\"font-family: sans-serif; white-space: normal;\">"
                + escaped.replace("\r\n", "\n").replace("\n", "<br>\n")
                + "</div>";
    }

    private static String escapeHtml(String text) {
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
