package dk.trustworks.intranet.logging;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JBossLog
@ApplicationScoped
public class ApiUsageLogService {

    @Transactional
    public void record(String username, String method, String path, String viewId, long duration) {
        log.debugf("record user=%s method=%s path=%s viewId=%s duration=%dms", username, method, path, viewId, duration);
        ApiUsageLog logEntry = new ApiUsageLog();
        logEntry.setTimestamp(LocalDateTime.now());
        logEntry.setUsername(username);
        logEntry.setMethod(method);
        logEntry.setPath(path);
        logEntry.setViewId(viewId);
        logEntry.setDuration(duration);
        logEntry.persist();
    }

    public List<ApiUsageLog> search(LocalDate date, String path, String user) {
        log.debugf("search date=%s path=%s user=%s", date, path, user);
        StringBuilder ql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();
        if(date != null) {
            ql.append(" and DATE(timestamp) = :day");
            params.put("day", date);
        }
        if(path != null && !path.isEmpty()) {
            ql.append(" and path = :path");
            params.put("path", path);
        }
        if(user != null && !user.isEmpty()) {
            ql.append(" and username = :user");
            params.put("user", user);
        }
        return ApiUsageLog.find(ql.toString(), params).list();
    }

    public long countByPath(String path) {
        log.debugf("countByPath path=%s", path);
        return ApiUsageLog.count("path", path);
    }

    public long countByPathAndDay(String path, LocalDate day) {
        log.debugf("countByPathAndDay path=%s day=%s", path, day);
        return ApiUsageLog.count("path = ?1 and DATE(timestamp) = ?2", path, day);
    }

    public Stats performanceStats(String path, LocalDate day) {
        log.debugf("performanceStats path=%s day=%s", path, day);
        String query = "path = ?1" + (day != null ? " and DATE(timestamp) = ?2" : "");
        List<ApiUsageLog> logs = day != null ? ApiUsageLog.find(query, path, day).list() : ApiUsageLog.find(query, path).list();
        long max = 0;
        long min = 0;
        double avg = 0;
        if(!logs.isEmpty()) {
            max = logs.stream().mapToLong(ApiUsageLog::getDuration).max().orElse(0);
            min = logs.stream().mapToLong(ApiUsageLog::getDuration).min().orElse(0);
            avg = logs.stream().mapToLong(ApiUsageLog::getDuration).average().orElse(0);
        }
        return new Stats(max, min, avg);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Stats {
        private long max;
        private long min;
        private double avg;
    }
}
