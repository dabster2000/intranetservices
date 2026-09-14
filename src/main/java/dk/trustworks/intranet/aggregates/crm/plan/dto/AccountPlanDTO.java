package dk.trustworks.intranet.aggregates.crm.plan.dto;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;

import java.time.LocalDate;
import java.util.List;

/**
 * The whole account plan, in the shape the tab already renders.
 *
 * <p>This mirrors {@code IAccountPlan} in {@code src/lib/crm/accountPlanTypes.ts} field
 * for field, on purpose. That file was written as the contract a backend would have to
 * fill, the tab and its eight dialogs are built against it and tested, and reshaping the
 * payload here would mean rewriting a UI nobody asked to change.
 *
 * <p><b>Nothing derived is in here.</b> Plan freshness, the missing-field list, the live
 * value behind a RATE objective, the suggested actions, the contradictions and "what
 * changed since the last review" stay in the frontend's {@code planDerivations.ts}, which
 * computes them from these records plus the page's real contracts and leads. A derived
 * value that gets stored is a derived value that goes stale.
 *
 * <p>Dates are {@code LocalDate} (serialised {@code yyyy-MM-dd}) wherever the UI treats
 * them as calendar days, which is everywhere except the audit-ish timestamps. The frontend
 * compares them as strings, so a time component would break every {@code >=} in
 * {@code planDerivations.ts}.
 */
public record AccountPlanDTO(
        String clientUuid,
        String status,
        int version,
        LocalDate updatedAt,
        PersonDTO updatedBy,
        LocalDate nextReview,
        PlanHealthDTO health,
        List<PlanSentenceDTO> sentences,
        List<PlanObjectiveDTO> objectives,
        List<PlanActionDTO> actions,
        List<PlanStakeholderDTO> stakeholders,
        List<PlanReviewDTO> reviews,
        PlanSnapshotDTO snapshot) {

    /** {@code rag} is null when nobody has assessed the plan — never a blank green (rule 8). */
    public record PlanHealthDTO(String rag, String why, PersonDTO setBy, LocalDate setAt) {
    }

    /** {@code by} is who WROTE the sentence, not who last confirmed the plan. */
    public record PlanSentenceDTO(String slot, String text, PersonDTO by, LocalDate validatedAt) {
    }

    public record PlanObjectiveDTO(
            String id,
            String category,
            String title,
            MeasureDTO measure,
            LocalDate targetDate,
            PersonDTO owner,
            String rag,
            String ragWhy,
            List<String> linkedLeadUuids,
            /** The sector objective this one serves (V592), and its title for the chip; null when none. */
            String sectorObjectiveUuid,
            String sectorObjectiveTitle) {

        /**
         * @param hold true when the target is a floor to hold rather than a level to reach
         */
        public record MeasureDTO(
                String kind,
                String label,
                String baseline,
                String target,
                String unit,
                boolean hold) {
        }
    }

    public record PlanActionDTO(
            String id,
            String title,
            String how,
            PersonDTO owner,
            LocalDate due,
            String cadence,
            LocalDate nextDue,
            String status,
            String priority,
            String objectiveId,
            String signalUuid,
            String stakeholderId,
            String result,
            LocalDate closedAt,
            String fromSuggestionId) {
    }

    /**
     * @param personUuid the {@code account_person} row this seat stars (spec §3.6), and the
     *                   join key the plan tab uses to find the person's coverage in the
     *                   relationships read. Null on a seat typed into the plan by hand, and
     *                   on a row promoted from a signal — those fall back to matching by
     *                   name, which is all a signal ever carried.
     * @param unit       null on a starred person: the registry knows a name and sometimes a
     *                   title, never an org unit, and "CIO, " with an empty tail is worse
     *                   than the title alone
     */
    public record PlanStakeholderDTO(
            String id,
            String personUuid,
            String name,
            String roleLabel,
            String title,
            String unit,
            String buying,
            String influence,
            LocalDate validatedAt,
            String fromSignalUuid,
            List<PlanRelationDTO> relations) {

        /**
         * Where the owner wants this relationship, and nothing about where it is today.
         *
         * <p>{@code current}, {@code lastInteraction} and {@code source} left the payload
         * with their columns in the 2026-09-14 cut (V606): how well a colleague knows
         * somebody is now that colleague's own claim and the calendar's {@code MET} edges,
         * both of which the relationships read carries. The plan keeps only the target,
         * because only the account owner can say what it should be.
         *
         * @param assessedBy who set the target, and {@code assessedAt} when — a number
         *                   nobody stands behind is an opinion pretending to be a fact
         */
        public record PlanRelationDTO(
                PersonDTO person,
                int target,
                String role,
                PersonDTO assessedBy,
                LocalDate assessedAt) {
        }
    }

    public record PlanReviewDTO(
            String id,
            LocalDate date,
            int version,
            List<PersonDTO> attendees,
            String outcome,
            List<String> decisions,
            LocalDate nextReview) {
    }

    /** The frozen photograph taken when the last review closed (rule 9). */
    public record PlanSnapshotDTO(
            LocalDate date,
            int version,
            String health,
            java.util.Map<String, String> objectiveRags,
            List<String> stakeholderIds,
            FactsDTO facts) {

        public record FactsDTO(
                Double weightedRate,
                int consultants,
                int openLeads,
                double weightedPipeline) {
        }
    }
}
