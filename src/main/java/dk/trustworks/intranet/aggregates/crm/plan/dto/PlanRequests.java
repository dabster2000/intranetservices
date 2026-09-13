package dk.trustworks.intranet.aggregates.crm.plan.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The request bodies the plan endpoints accept — one per reducer in the frontend's
 * {@code accountPlanReducers.ts}.
 *
 * <p>Grouped in one file because each is two to six fields and eleven separate files of
 * four lines would be harder to read, not easier. They are nested records, so the wire
 * names are unchanged.
 *
 * <p><b>Optional means optional.</b> Fields are boxed and a null means "leave it alone";
 * clearing a value takes an explicit {@code clear*} flag. A JSON null and an absent key
 * are indistinguishable after deserialisation, and a form that forgot a field must not
 * wipe it.
 */
public final class PlanRequests {

    private PlanRequests() {
    }

    /**
     * Starting a plan on an account that has none. The first sentence and the first
     * objective are asked for together, because a plan that is only a title is the thing
     * this model exists to prevent.
     */
    public record StartPlanRequest(
            String why,
            String current,
            String desired,
            String question,
            LocalDate nextReview,
            String healthRag,
            String healthWhy) {
    }

    /**
     * "Still true", and the health assessment behind it.
     *
     * <p>{@code confirmOnly} is the Still-true button: it re-dates the plan without
     * changing anything, which is the one thing an owner does most often and should not
     * have to open a dialog for.
     */
    public record PlanPatchRequest(
            boolean confirmOnly,
            String healthRag,
            boolean clearHealthRag,
            String healthWhy,
            LocalDate nextReview,
            boolean clearNextReview) {
    }

    /** One of the four sentences. An empty text deletes the sentence rather than storing "". */
    public record SentenceRequest(String text) {
    }

    public record ObjectiveRequest(
            String category,
            String title,
            String measureKind,
            String measureLabel,
            String baseline,
            String target,
            String unit,
            Boolean hold,
            LocalDate targetDate,
            String ownerUuid,
            String rag,
            boolean clearRag,
            String ragWhy,
            List<String> linkedLeadUuids) {
    }

    public record ActionRequest(
            String title,
            String how,
            String ownerUuid,
            LocalDate due,
            boolean clearDue,
            String cadence,
            boolean clearCadence,
            String priority,
            String objectiveUuid,
            boolean clearObjective,
            String signalUuid,
            String stakeholderUuid,
            String status,
            String result,
            String fromSuggestionId) {
    }

    public record StakeholderRequest(
            String name,
            String roleLabel,
            String title,
            String unit,
            String buying,
            String influence,
            String fromSignalUuid,
            List<RelationRequest> relations) {

        /** One Trustworks person's rating of this stakeholder. Replaces the whole set. */
        public record RelationRequest(
                String userUuid,
                int current,
                int target,
                String role,
                LocalDate lastInteraction,
                String source) {
        }
    }

    /**
     * Closing a review. The live facts come from the caller because they are what the
     * PAGE was showing when the meeting happened — the weighted pipeline and rate the
     * attendees actually looked at, not what a recomputation would say afterwards.
     */
    public record ReviewRequest(
            LocalDate date,
            List<String> attendeeUuids,
            String outcome,
            List<String> decisions,
            LocalDate nextReview,
            Map<String, String> objectiveRags,
            Double weightedRate,
            int consultants,
            int openLeads,
            double weightedPipeline) {
    }
}
