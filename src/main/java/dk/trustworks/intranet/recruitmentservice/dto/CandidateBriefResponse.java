package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * The <b>restricted candidate brief</b> (go-live decisions D10–D12): the
 * only recruitment surface reachable by someone holding no recruitment role
 * — an assigned interviewer, or a member of one of the candidate's
 * positions' circles.
 *
 * <h3>What rides here, and what deliberately does not</h3>
 * Everything an interviewer needs to walk in prepared: who the person is,
 * how to reach their material (CV and the other application attachments),
 * which position they applied to, when and where the interview is, what to
 * probe for, what they wrote in the form, and whether the viewer's own
 * scorecard is still outstanding.
 * <p>
 * Absent by construction — not filtered client-side, never serialized:
 * <ul>
 *   <li>salary expectations and offer terms;</li>
 *   <li>pipeline stage, candidate status, decisions and stage history;</li>
 *   <li>other people's scorecards, before or after the blind unlock;</li>
 *   <li>notes of any kind, private or not;</li>
 *   <li>the event timeline;</li>
 *   <li>the referrer's identity;</li>
 *   <li>any other candidate.</li>
 * </ul>
 * A viewer who also holds ADMIN/HR/RECRUITMENT/TEAMLEAD may read this, but
 * the frontend sends them to the full profile instead — the brief is
 * additive, never a downgrade.
 *
 * @param documents  the application attachments only (CV, cover letter and
 *                   unclassified uploads). Contract drafts, signed
 *                   documents, appendices and identity documents are never
 *                   included — see {@code CandidateBriefService}.
 * @param employment the workplaces the AI brief read out of the CV
 *                   (brief-v2), newest first. This is a RE-STATEMENT of the
 *                   CV the viewer can already open, in a form they can read
 *                   at a glance — not an addition to what the brief admits.
 *                   Empty when the brief flag is off, when no brief has been
 *                   generated for one of the viewer's own interviews'
 *                   applications, or when the CV showed no history.
 */
public record CandidateBriefResponse(
        String candidateUuid,
        String name,
        String linkedinUrl,
        List<CandidateDocument> documents,
        List<FormAnswer> answers,
        List<CandidateBriefInterview> interviews,
        List<CandidateAiStateResponse.AiEmployment> employment
) {
}
