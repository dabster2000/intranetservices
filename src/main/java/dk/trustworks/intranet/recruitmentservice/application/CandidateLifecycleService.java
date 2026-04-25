package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.Application;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ApplicationStage;
import dk.trustworks.intranet.recruitmentservice.domain.enums.CandidateState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

@ApplicationScoped
public class CandidateLifecycleService {

    private static final Set<ApplicationStage> CLOSING_STAGES = EnumSet.of(
            ApplicationStage.REJECTED,
            ApplicationStage.WITHDRAWN,
            ApplicationStage.TALENT_POOL,
            ApplicationStage.CONVERTED);

    @Transactional
    public void onApplicationStageChanged(String candidateUuid, ApplicationStage newStage) {
        Candidate c = Candidate.findById(candidateUuid);
        if (c == null) return;

        long activeCount = Application.count(
                "candidateUuid = ?1 and stage not in (?2, ?3, ?4, ?5)",
                candidateUuid,
                ApplicationStage.REJECTED,
                ApplicationStage.WITHDRAWN,
                ApplicationStage.TALENT_POOL,
                ApplicationStage.CONVERTED);

        if (activeCount > 0 && c.state == CandidateState.TALENT_POOL) {
            c.state = CandidateState.ACTIVE;
            c.addedToPoolAt = null;
            c.updatedAt = LocalDateTime.now();
            return;
        }

        if (CLOSING_STAGES.contains(newStage) && activeCount == 0 && c.state == CandidateState.ACTIVE) {
            c.state = CandidateState.TALENT_POOL;
            c.addedToPoolAt = LocalDateTime.now();
            c.updatedAt = LocalDateTime.now();
        }
    }
}
