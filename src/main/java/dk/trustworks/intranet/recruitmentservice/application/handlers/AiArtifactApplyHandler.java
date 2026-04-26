package dk.trustworks.intranet.recruitmentservice.application.handlers;

import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;

/**
 * Strategy contract for applying a reviewed/overridden AI artifact onto its
 * subject aggregate (e.g. patching a Candidate from a CV_EXTRACTION result).
 *
 * <p>Implementations are discovered via CDI {@code @Inject Instance<...>} in
 * {@link dk.trustworks.intranet.recruitmentservice.application.AiArtifactService}.
 * Slice 2 ships the contract only — concrete handlers land in Phase E (Slice 3).
 * Until then the handlers iterator is empty, which is intentional: accepting
 * an artifact still records the review state, with no entity patch effect.
 */
public interface AiArtifactApplyHandler {

    /** True when this handler knows how to apply artifacts of the given {@code kind}. */
    boolean handles(AiArtifactKind kind);

    /**
     * Apply the artifact to its subject aggregate.
     *
     * @param artifact     the reviewed artifact (state already mutated by the service)
     * @param overrideJson reviewer-supplied override payload, or {@code null} for plain accept
     */
    void apply(AiArtifact artifact, String overrideJson);
}
