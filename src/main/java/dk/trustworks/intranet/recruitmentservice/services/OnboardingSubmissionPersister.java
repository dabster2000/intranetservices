package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadSubmission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Narrow transactional boundary around the {@code onboarding_upload_submissions}
 * audit-row insert. Lives in its own bean so the surrounding
 * {@link OnboardingUploadService#handleUpload} can execute the slow
 * S3/SharePoint upload <i>outside</i> any DB transaction (no connection held
 * for the 1–5 s round trip), then call into here for a small write-only TX.
 *
 * <p>Pattern: persist-then-upload was the original shape; we deliberately
 * inverted it (upload-then-persist) so a persist failure can drive a
 * compensating storage delete in the caller — a storage failure cannot leave
 * an audit row behind.</p>
 *
 * <p>Errors (DB constraint violations, etc.) propagate to the caller for
 * compensation handling; this bean does not swallow them.</p>
 */
@ApplicationScoped
public class OnboardingSubmissionPersister {

    /**
     * Persist the given submission row in a fresh, narrow transaction.
     * Throws {@link jakarta.persistence.PersistenceException} (or its subclasses,
     * including the wrapped {@code org.hibernate.exception.ConstraintViolationException})
     * on unique-key collision against {@code uk_ous_token_doctype}.
     */
    @Transactional
    public void persist(OnboardingUploadSubmission row) {
        row.persist();
    }
}
