package dk.trustworks.intranet.aggregates.crm.sector.dto;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.plan.dto.AccountPlanDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The sector plan, in the shape the plan components already render.
 *
 * <p>Deliberately the account plan's shape ({@code IAccountPlan} in
 * {@code accountPlanTypes.ts}) with two additions per objective — the account objectives
 * SERVING it and the colour DERIVED from them — and no stakeholders. The frontend reuses
 * the account plan's strip, sentences, objectives, actions and reviews unchanged.
 *
 * <p>Nothing derived is stored. {@code derivedRag} and {@code servedBy} are computed on
 * every read from {@code client_plan_objective.sector_objective_uuid}.
 */
public record SectorPlanDTO(
        String segment,
        String label,
        String status,
        int version,
        LocalDate updatedAt,
        PersonDTO updatedBy,
        LocalDate nextReview,
        AccountPlanDTO.PlanHealthDTO health,
        List<AccountPlanDTO.PlanSentenceDTO> sentences,
        List<SectorObjectiveDTO> objectives,
        List<AccountPlanDTO.PlanActionDTO> actions,
        List<AccountPlanDTO.PlanReviewDTO> reviews,
        SectorSnapshotDTO snapshot) {

    /**
     * @param rag        the sector lead's own assessment, null when not assessed
     * @param derivedRag the worst of the serving account objectives' effective colours,
     *                   null when none of them is assessed
     * @param servedBy   the open account objectives that declared they serve this one
     */
    public record SectorObjectiveDTO(
            String id,
            String category,
            String title,
            AccountPlanDTO.PlanObjectiveDTO.MeasureDTO measure,
            LocalDate targetDate,
            PersonDTO owner,
            String rag,
            String ragWhy,
            List<String> linkedLeadUuids,
            String derivedRag,
            List<ServedByDTO> servedBy) {
    }

    /** One account objective serving a sector objective, with its own effective colour. */
    public record ServedByDTO(String clientUuid, String clientName, String objectiveId, String title, String rag) {
    }

    /** The frozen photograph taken when the last review closed (rule 9). */
    public record SectorSnapshotDTO(
            LocalDate date,
            int version,
            String health,
            Map<String, String> objectiveRags,
            List<String> stakeholderIds,
            FactsDTO facts) {

        public record FactsDTO(
                Double weightedRate,
                int consultants,
                int openLeads,
                double weightedPipeline,
                double fyRevenue,
                Map<String, Integer> accountsByBand,
                Map<String, Integer> planCoverage) {
        }
    }
}
