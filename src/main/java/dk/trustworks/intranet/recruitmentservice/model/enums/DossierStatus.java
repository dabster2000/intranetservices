package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Lifecycle status of a {@code CandidateDossier}.
 * <p>
 * A dossier is born {@link #OPEN} and is closed (becomes {@link #CLOSED})
 * automatically when its parent candidate enters a terminal state.
 * <p>
 * Persisted as {@code VARCHAR(20)} via {@code @Enumerated(EnumType.STRING)};
 * the database also enforces the value set with {@code chk_cd_status_enum}.
 */
public enum DossierStatus {
    OPEN,
    CLOSED
}
