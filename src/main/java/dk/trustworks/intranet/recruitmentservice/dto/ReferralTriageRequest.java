package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Request body for {@code POST /recruitment/referrals/{uuid}/triage} —
 * the recruiter's one-shot decision on a SUBMITTED referral (ATS plan §P6).
 * <p>
 * Two legs, selected by {@link #action} ({@code CREATE_CANDIDATE} |
 * {@code DISMISS} — parsed explicitly, unknown → 400):
 * <ul>
 *   <li><b>CREATE_CANDIDATE</b>: {@link #firstName}/{@link #lastName}
 *       required (the recruiter splits the submitted full name);
 *       {@link #email}/{@link #phone}/{@link #linkedinUrl} prefilled from
 *       the referral, editable; {@link #sponsoringPartnerUuid} present →
 *       source {@code PARTNER_REFERRAL} (the partner mandate), else
 *       {@code REFERRAL}; {@link #positionUuid} optionally attaches an
 *       application immediately (OPEN positions only — all P4 create
 *       invariants apply).</li>
 *   <li><b>DISMISS</b>: {@link #dismissReason} required
 *       ({@code DUPLICATE} | {@code NOT_RELEVANT} | {@code OTHER}).</li>
 * </ul>
 */
public record ReferralTriageRequest(
        String action,
        String firstName,
        String lastName,
        String email,
        String phone,
        String linkedinUrl,
        String sponsoringPartnerUuid,
        String relevantTeamleadUuid,
        String positionUuid,
        String dismissReason
) {
}
