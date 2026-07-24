package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * How internal copies of a candidate email are addressed.
 * <p>
 * {@link #BCC} is the default and the only safe choice for rejections: a
 * visible CC tells the candidate exactly who was involved in judging them
 * and invites a Reply-All straight into an internal thread. {@link #CC}
 * exists for the templates where naming the people IS the message — an
 * interview confirmation that says "du skal møde Anna og Bo" should show
 * Anna and Bo in the header.
 */
public enum RecruitmentEmailCopyMode {
    /** Invisible to the candidate — the default. */
    BCC,
    /** Visible in the email header, and reply-all-able. */
    CC
}
