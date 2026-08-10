package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Manual send from the compose dialog (P15). Subject/body arrive FINAL —
 * the recruiter may have edited the rendered template, and the send is
 * verbatim (the P16 AI composer reuses this contract for its
 * recruiter-reviewed drafts). {@code templateUuid} is provenance only.
 * <p>
 * {@code copyUserUuids} is the per-send override of the template's copy
 * policy: {@code null} means "use whatever the template asks for", an
 * empty list means "copy nobody" (an explicit recruiter decision). The
 * server re-authorizes every uuid against the candidate's read matrix, so
 * a caller cannot copy someone into a candidate they may not see — and
 * cannot address an arbitrary external party at all, since the wire
 * carries people, not addresses.
 */
public record SendEmailRequest(
        String templateUuid,
        String applicationUuid,
        String subject,
        String body,
        /** PLAIN or HTML; absent means PLAIN, i.e. the pre-rich-text contract. */
        String bodyFormat,
        List<String> copyUserUuids,
        String copyMode
) {
}
