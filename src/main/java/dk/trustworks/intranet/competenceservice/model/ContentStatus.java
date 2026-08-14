package dk.trustworks.intranet.competenceservice.model;

/**
 * The lifecycle of one content version.
 *
 * <p>At most one ACTIVE and at most one DRAFT per (requirement, kind), enforced by the
 * {@code uk_competence_content_active} / {@code uk_competence_content_draft} unique
 * indexes over trigger-written key columns (V495). ARCHIVED rows are unconstrained —
 * their key is NULL and MariaDB unique indexes ignore NULLs.
 */
public enum ContentStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED
}
