package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Request body for the ADMIN candidate hard delete
 * ({@code POST /recruitment/candidates/{uuid}/hard-delete}).
 *
 * <p>Deliberately carries <b>no</b> bean-validation annotations: bean
 * validation is inert in this backend (no {@code quarkus-hibernate-validator}
 * on the classpath), so every rule here is checked explicitly in
 * {@code RecruitmentResource}. Writing {@code @NotBlank} would look like a
 * guard and be none.</p>
 *
 * @param confirmText the candidate's exact {@code firstName + " " + lastName},
 *                    typed by the caller. Compared server-side; a mismatch is
 *                    {@code 400 {"error":"CONFIRMATION_MISMATCH"}}, the same
 *                    contract the DPO anonymize action already uses
 *                    ({@code RecruitmentGdprResource.anonymize}). The UI
 *                    dialog is convenience, not the guard.
 * @param reason      why the candidate is being removed. Required and
 *                    non-trivial — it is written to the deletion ledger and
 *                    is, after the cascade, the only explanation that
 *                    survives.
 */
public record HardDeleteRequest(String confirmText, String reason) {
}
