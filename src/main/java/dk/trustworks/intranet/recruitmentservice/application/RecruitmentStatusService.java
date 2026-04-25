package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.RecruitmentStatusEntity;
import dk.trustworks.intranet.recruitmentservice.domain.enums.RecruitmentStatusValue;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ScopeKind;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class RecruitmentStatusService {

    @Transactional
    public RecruitmentStatusEntity upsert(ScopeKind kind, String scopeId,
                                          RecruitmentStatusValue value, String reason, String actorUuid) {
        RecruitmentStatusEntity row = RecruitmentStatusEntity
                .findById(new RecruitmentStatusEntity.Id(kind, scopeId));
        if (row == null) {
            row = new RecruitmentStatusEntity();
            row.scopeKind = kind;
            row.scopeId = scopeId;
        }
        row.status = value;
        row.reason = reason;
        row.changedByUuid = actorUuid;
        row.changedAt = LocalDateTime.now();
        row.persist();
        return row;
    }

    public Optional<RecruitmentStatusEntity> find(ScopeKind kind, String scopeId) {
        return Optional.ofNullable(
                RecruitmentStatusEntity.findById(new RecruitmentStatusEntity.Id(kind, scopeId)));
    }

    public List<RecruitmentStatusEntity> listAll() {
        PanacheQuery<RecruitmentStatusEntity> q = RecruitmentStatusEntity.findAll();
        return q.list();
    }
}
