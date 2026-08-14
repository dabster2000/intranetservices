package dk.trustworks.intranet.competenceservice.model;

/**
 * The two independently versioned artefacts a requirement carries.
 *
 * <p>Separate on purpose (spec §2.2): a typo fix in the reading material must not
 * invalidate everyone's passed test, and a sharpened question must not force everyone to
 * re-read thirteen screens.
 */
public enum ContentKind {
    COURSE,
    TEST
}
