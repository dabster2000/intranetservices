package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Optional recruiter edits applied at approval (P15). Blank/absent fields
 * keep the queued rendered snapshot — including {@code copyUserUuids}
 * ({@code null} = the copy list snapshotted at queue time) and
 * {@code copyMode}. Supplied uuids are re-authorized at approval time,
 * not trusted from the snapshot: involvement can change while a row waits.
 */
public record ApproveEmailRequest(
        String subject,
        String body,
        List<String> copyUserUuids,
        String copyMode
) {
}
