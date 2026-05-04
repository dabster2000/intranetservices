package dk.trustworks.intranet.recruitmentservice.model.snapshot;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, frozen-at-Send-time snapshot of the signer configuration on a
 * dossier when a revision was allocated. The order of {@link Signer}s in the
 * list is the signing order.
 * <p>
 * The signer record mirrors the columns on {@code template_default_signers}
 * but is decoupled from that JPA entity so changes to the template do not
 * mutate historic snapshots.
 */
public record SignersConfigSnapshot(List<Signer> signers) {

    public SignersConfigSnapshot {
        Objects.requireNonNull(signers, "signers must not be null");
        signers = List.copyOf(signers);
    }

    public static SignersConfigSnapshot empty() {
        return new SignersConfigSnapshot(Collections.emptyList());
    }

    /**
     * One signer in a signing flow.
     *
     * @param name         display name
     * @param email        signer email (required for all flows)
     * @param role         human-readable role (e.g. "Candidate", "Manager")
     * @param signerGroup  NextSign signing group identifier
     * @param signing      whether this party performs an actual signature
     *                     ({@code true}) or merely receives the document for
     *                     review ({@code false})
     * @param needsCpr     whether the signer must verify identity with CPR
     * @param displayOrder 1-based ordering within the dossier
     */
    public record Signer(
            String name,
            String email,
            String role,
            String signerGroup,
            boolean signing,
            boolean needsCpr,
            int displayOrder
    ) {
        public Signer {
            Objects.requireNonNull(email, "email must not be null");
        }
    }
}
