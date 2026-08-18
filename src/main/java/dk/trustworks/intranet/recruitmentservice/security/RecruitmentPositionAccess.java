package dk.trustworks.intranet.recruitmentservice.security;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * The "may this actor attach something to this position?" guard, in one
 * place.
 *
 * <p>Three call sites now need the identical two-step check —
 * {@code ReferralService.triageCreate} (P14), the atomic
 * create-with-position path on {@code RecruitmentResource.createCandidate},
 * and the standalone attach on {@code RecruitmentApplicationResource.create}
 * — so the rule lives here instead of being copied a third time.
 *
 * <p>The two steps answer deliberately different statuses:
 * <ol>
 *   <li><b>Not readable → 404.</b> An existing-but-invisible partner-track
 *       position must be indistinguishable from a nonexistent one; a 403
 *       would confirm that a confidential req exists. This is the module's
 *       standing convention (see {@code RecruitmentApplicationResource}'s
 *       {@code requireVisiblePosition} and the {@code canReadPosition}
 *       javadoc).</li>
 *   <li><b>Readable but not decidable → 403.</b> To a team lead who can see
 *       a position on the board, a 404 would just look broken; they are
 *       entitled to know the position is real and that the refusal is about
 *       their rights on it. Reading widely never implies deciding widely
 *       ({@code canDecideOnApplication}).</li>
 * </ol>
 */
public final class RecruitmentPositionAccess {

    private RecruitmentPositionAccess() {
    }

    /**
     * Resolve {@code positionUuid} and assert the actor may attach an
     * application to it.
     *
     * @return the managed position, never {@code null}
     * @throws NotFoundException       the uuid is blank, resolves to nothing,
     *                                 or resolves to a position this actor
     *                                 may not read
     * @throws WebApplicationException 403 when the actor may read the
     *                                 position but holds no decision rights
     *                                 on it
     */
    public static RecruitmentPosition requireDecidablePosition(RecruitmentVisibility visibility,
                                                               String viewerUuid,
                                                               String positionUuid) {
        String trimmed = positionUuid == null ? null : positionUuid.trim();
        RecruitmentPosition position = trimmed == null || trimmed.isEmpty()
                ? null
                : RecruitmentPosition.findById(trimmed);
        return requireDecidable(visibility, viewerUuid, positionUuid, position);
    }

    /**
     * The decision half of {@link #requireDecidablePosition}, split out so
     * the status mapping is unit-testable without a database: the caller
     * supplies the already-resolved row (or {@code null}).
     */
    static RecruitmentPosition requireDecidable(RecruitmentVisibility visibility,
                                                String viewerUuid,
                                                String requestedUuid,
                                                RecruitmentPosition position) {
        if (position == null
                || viewerUuid == null || viewerUuid.isBlank()
                || !visibility.canReadPosition(viewerUuid, position)) {
            throw new NotFoundException("Position not found: " + requestedUuid);
        }
        if (!visibility.canDecideOnApplication(viewerUuid, position)) {
            throw new WebApplicationException(
                    "Only the recruiter, the hiring owner or the position's teamlead may attach to this position",
                    Response.Status.FORBIDDEN);
        }
        return position;
    }
}
