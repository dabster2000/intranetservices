package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Response body for {@code POST /recruitment/candidates/{uuid}/convert}.
 * Returned synchronously after the convert transaction commits — note that
 * the SharePoint copy-and-delete is queued post-commit and is reported
 * separately via {@code sharepoint_move_status} on the candidate (read back
 * via {@code GET /recruitment/candidates/{uuid}}).
 *
 * @param newUserUuid              UUID of the newly provisioned {@code users}
 *                                 row
 * @param candidateUuid            UUID of the candidate that was converted
 * @param status                   constant {@code "HIRED"} for clarity in
 *                                 logs and clients
 * @param signingCasesTransferred  count of {@code signing_cases} rows whose
 *                                 {@code user_uuid} was repointed to the
 *                                 newly created user
 */
public record ConvertResponse(
        String newUserUuid,
        String candidateUuid,
        String status,
        int signingCasesTransferred
) {
    public static ConvertResponse hired(String newUserUuid, String candidateUuid, int signingCasesTransferred) {
        return new ConvertResponse(newUserUuid, candidateUuid, "HIRED", signingCasesTransferred);
    }
}
