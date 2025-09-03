package dk.trustworks.intranet.batch.monitoring;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.time.LocalDateTime;
import java.util.*;

@ApplicationScoped
public class BatchJobTrackingQuery {

    @Inject
    EntityManager em;

    public PageResult<BatchJobExecutionTracking> search(
            String jobName,
            String status,
            String result,
            Boolean runningOnly,
            LocalDateTime startFrom,
            LocalDateTime startTo,
            LocalDateTime endFrom,
            LocalDateTime endTo,
            int page,
            int size,
            String sort) {

        Map<String, Object> params = new HashMap<>();
        StringBuilder jpql = new StringBuilder("select e from BatchJobExecutionTracking e where 1=1");
        StringBuilder countJpql = new StringBuilder("select count(e) from BatchJobExecutionTracking e where 1=1");

        if (jobName != null && !jobName.isBlank()) {
            jpql.append(" and e.jobName = :jobName");
            countJpql.append(" and e.jobName = :jobName");
            params.put("jobName", jobName);
        }
        if (status != null && !status.isBlank()) {
            jpql.append(" and upper(e.status) = :status");
            countJpql.append(" and upper(e.status) = :status");
            params.put("status", status.toUpperCase(Locale.ROOT));
        }
        if (result != null && !result.isBlank()) {
            jpql.append(" and upper(e.result) = :result");
            countJpql.append(" and upper(e.result) = :result");
            params.put("result", result.toUpperCase(Locale.ROOT));
        }
        if (runningOnly != null && runningOnly) {
            jpql.append(" and e.status in ('STARTED','STARTING')");
            countJpql.append(" and e.status in ('STARTED','STARTING')");
        }
        if (startFrom != null) {
            jpql.append(" and e.startTime >= :startFrom");
            countJpql.append(" and e.startTime >= :startFrom");
            params.put("startFrom", startFrom);
        }
        if (startTo != null) {
            jpql.append(" and e.startTime <= :startTo");
            countJpql.append(" and e.startTime <= :startTo");
            params.put("startTo", startTo);
        }
        if (endFrom != null) {
            jpql.append(" and e.endTime >= :endFrom");
            countJpql.append(" and e.endTime >= :endFrom");
            params.put("endFrom", endFrom);
        }
        if (endTo != null) {
            jpql.append(" and e.endTime <= :endTo");
            countJpql.append(" and e.endTime <= :endTo");
            params.put("endTo", endTo);
        }

        String orderBy;
        if (sort == null || sort.isBlank()) {
            orderBy = " order by e.startTime desc";
        } else {
            // very small whitelist to avoid injection
            switch (sort) {
                case "startTime,asc": orderBy = " order by e.startTime asc"; break;
                case "startTime,desc": orderBy = " order by e.startTime desc"; break;
                case "endTime,asc": orderBy = " order by e.endTime asc"; break;
                case "endTime,desc": orderBy = " order by e.endTime desc"; break;
                default: orderBy = " order by e.startTime desc"; break;
            }
        }
        jpql.append(orderBy);

        TypedQuery<BatchJobExecutionTracking> q = em.createQuery(jpql.toString(), BatchJobExecutionTracking.class);
        TypedQuery<Long> cq = em.createQuery(countJpql.toString(), Long.class);
        params.forEach((k, v) -> { q.setParameter(k, v); cq.setParameter(k, v); });

        q.setFirstResult(Math.max(0, page) * Math.max(1, size));
        q.setMaxResults(Math.max(1, Math.min(200, size)));

        List<BatchJobExecutionTracking> items = q.getResultList();
        long total = cq.getSingleResult();
        return new PageResult<>(items, page, size, total);
    }

    public Optional<BatchJobExecutionTracking> findByExecutionId(long executionId) {
        List<BatchJobExecutionTracking> list = em.createQuery(
                        "select e from BatchJobExecutionTracking e where e.executionId = :id",
                        BatchJobExecutionTracking.class)
                .setParameter("id", executionId)
                .getResultList();
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Summary summary(String jobName) {
        StringBuilder jpql = new StringBuilder("select e.status, e.result, count(e) from BatchJobExecutionTracking e where 1=1");
        Map<String, Object> params = new HashMap<>();
        if (jobName != null && !jobName.isBlank()) {
            jpql.append(" and e.jobName = :jobName");
            params.put("jobName", jobName);
        }
        jpql.append(" group by e.status, e.result");
        var q = em.createQuery(jpql.toString(), Object[].class);
        params.forEach(q::setParameter);
        Map<String, Long> byStatus = new HashMap<>();
        Map<String, Long> byResult = new HashMap<>();
        for (Object[] row : q.getResultList()) {
            String status = (String) row[0];
            String result = (String) row[1];
            long count = (Long) row[2];
            byStatus.merge(status, count, Long::sum);
            if (result != null) byResult.merge(result, count, Long::sum);
        }
        return new Summary(byStatus, byResult);
    }

    public static class PageResult<T> {
        public final List<T> items;
        public final int page;
        public final int size;
        public final long total;
        public PageResult(List<T> items, int page, int size, long total) {
            this.items = items; this.page = page; this.size = size; this.total = total;
        }
    }

    public static class Summary {
        public final Map<String, Long> byStatus;
        public final Map<String, Long> byResult;
        public Summary(Map<String, Long> byStatus, Map<String, Long> byResult) {
            this.byStatus = byStatus; this.byResult = byResult;
        }
    }
}
