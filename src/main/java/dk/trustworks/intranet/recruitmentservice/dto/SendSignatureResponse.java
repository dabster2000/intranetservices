package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Envelope response for {@code POST /recruitment/candidates/{uuid}/dossier/send-signature}.
 * <p>
 * The endpoint returns the freshly-allocated {@link RevisionResponse} alongside
 * {@code localPersistenceFailed} — a single boolean that is {@code true} when
 * NextSign accepted the signing case but at least one local DB write
 * ({@code signing_cases} or {@code candidate_dossier_revisions}) could not be
 * persisted. In that mode the BFF surfaces an "info / warning" toast instead
 * of "error", so users do not retry and create a duplicate NextSign case.
 * <p>
 * The flag is also mirrored as {@code X-Local-Persistence-Failed: true} on the
 * response headers for non-typed clients; see
 * {@code RecruitmentResource.LOCAL_PERSISTENCE_FAILED_HEADER}.
 *
 * @param revision               the dossier revision row that was (or would
 *                               have been) written; on terminal local-write
 *                               failure this is a synthesised, degraded
 *                               instance whose {@code uuid} is {@code null}
 * @param localPersistenceFailed {@code true} when one or more local DB writes
 *                               failed after NextSign accepted the case
 */
public record SendSignatureResponse(
        RevisionResponse revision,
        boolean localPersistenceFailed
) {
}
