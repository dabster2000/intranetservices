package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * How a candidate-email body is to be interpreted (ATS plan §P15, rich text).
 * <p>
 * The discriminator exists so the render path is never a guess. Sniffing a
 * stored body for markup would misread a plain Danish sentence containing
 * {@code R&D} or {@code 5 < 6} as HTML and serve a candidate mangled
 * entities — an unlogged, untestable branch between a GDPR-regulated
 * template and someone's inbox.
 * <ul>
 *   <li>{@link #PLAIN} — legacy plain text. HTML-escaped and newline-to-{@code <br>}
 *       converted at send time; exactly the pre-rich-text behaviour.</li>
 *   <li>{@link #HTML} — a sanitized HTML fragment
 *       ({@code RecruitmentEmailHtmlSanitizer}'s allow-list). Merge values
 *       are escaped as they are substituted, never the body itself.</li>
 * </ul>
 * Every pre-existing row defaults to {@code PLAIN} (V485): a template only
 * becomes {@code HTML} when a recruiter deliberately saves it from the rich
 * editor, which keeps a rollback to the previous backend harmless for every
 * template nobody has touched.
 */
public enum RecruitmentEmailBodyFormat {

    PLAIN,
    HTML;

    /** Null-safe parse for wire values; unknown/absent falls back to {@link #PLAIN}. */
    public static RecruitmentEmailBodyFormat parse(String value) {
        if (value == null || value.isBlank()) {
            return PLAIN;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PLAIN;
        }
    }

    public boolean isHtml() {
        return this == HTML;
    }
}
