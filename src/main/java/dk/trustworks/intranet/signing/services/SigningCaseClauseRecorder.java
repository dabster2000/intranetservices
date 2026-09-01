package dk.trustworks.intranet.signing.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.documentservice.services.ClauseCompositionService.CompositionPlan;
import dk.trustworks.intranet.documentservice.services.ClauseCompositionService.ResolvedClauseItem;
import dk.trustworks.intranet.signing.domain.SigningCase;
import dk.trustworks.intranet.signing.domain.SigningCaseClause;
import dk.trustworks.intranet.signing.repository.SigningCaseRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

/**
 * Writes the immutable {@code signing_case_clauses} snapshot for a sent
 * case (template-clauses spec §4.5) — the single source the Phase 3
 * {@code AgreementRecorder} reads on COMPLETED, identical for the wizard
 * and the dossier flow.
 * <p>
 * Runs after {@code saveMinimalCase}; a failure is logged, never
 * propagated — the case already exists in NextSign, and refusing the
 * response would push the user into re-sending a duplicate case (the
 * 2026-05-21 lesson).
 */
@JBossLog
@ApplicationScoped
public class SigningCaseClauseRecorder {

    @Inject
    SigningCaseRepository signingCaseRepository;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Snapshot the plan's items for the case. No-op for an empty plan.
     * Idempotent per case: existing rows mean a retry already recorded.
     */
    @Transactional
    public void record(String caseKey, CompositionPlan plan) {
        if (plan == null || plan.isEmpty()) {
            return;
        }
        try {
            SigningCase signingCase = signingCaseRepository.findByCaseKey(caseKey).orElse(null);
            if (signingCase == null || signingCase.getId() == null) {
                log.errorf("Cannot record %d clause snapshot rows for case %s: signing_cases row missing"
                        + " — the Phase 3 registry will not see this case's clauses", plan.items().size(), caseKey);
                return;
            }
            if (!SigningCaseClause.findByCase(signingCase.getId()).isEmpty()) {
                log.infof("Clause snapshot already recorded for case %s — skipping", caseKey);
                return;
            }
            for (ResolvedClauseItem item : plan.items()) {
                SigningCaseClause row = new SigningCaseClause();
                row.setSigningCaseId(signingCase.getId());
                row.setClauseUuid(item.clauseUuid());
                row.setClauseVersionUuid(item.clauseVersionUuid());
                row.setRenderMode(item.effectiveMode().name());
                row.setParameterValuesJson(item.parameterValues().isEmpty()
                        ? null
                        : objectMapper.writeValueAsString(item.parameterValues()));
                row.setCustomTitle(item.customTitle());
                row.setCustomText(item.customText());
                row.setDisplayOrder(item.displayOrder());
                row.persist();
            }
            log.infof("Recorded %d clause snapshot rows for case %s", plan.items().size(), caseKey);
        } catch (Exception e) {
            log.errorf(e, "Failed to record clause snapshot for case %s — the case is sent;"
                    + " the Phase 3 registry will miss it without a manual backfill", caseKey);
        }
    }
}
