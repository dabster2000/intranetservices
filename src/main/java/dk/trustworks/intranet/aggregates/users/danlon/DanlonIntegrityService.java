package dk.trustworks.intranet.aggregates.users.danlon;

import dk.trustworks.intranet.aggregates.users.danlon.dto.DanlonIntegrityReport;
import dk.trustworks.intranet.aggregates.users.danlon.dto.DanlonIntegrityReport.*;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.*;

/**
 * Read-only integrity report (spec §10). Surfaces existing data problems
 * for HR/payroll to resolve by hand — it NEVER edits or deletes any
 * existing Danløn value (AC9).
 */
@JBossLog
@ApplicationScoped
public class DanlonIntegrityService {

    @Inject
    EntityManager em;

    @Transactional
    public DanlonIntegrityReport buildReport() {
        return new DanlonIntegrityReport(findDuplicates(), findMissingIdActives(), findNonConforming());
    }

    @SuppressWarnings("unchecked")
    private List<DuplicateDanlon> findDuplicates() {
        // (danlon, useruuid) pairs for every danlon shared by >1 distinct user.
        List<Object[]> rows = em.createNativeQuery(
                "SELECT h.danlon, h.useruuid FROM user_danlon_history h " +
                "WHERE h.danlon IN (SELECT danlon FROM user_danlon_history " +
                "                    GROUP BY danlon HAVING COUNT(DISTINCT useruuid) > 1) " +
                "GROUP BY h.danlon, h.useruuid ORDER BY h.danlon")
                .getResultList();
        Map<String, List<Holder>> byNumber = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String danlon = (String) r[0];
            String useruuid = (String) r[1];
            byNumber.computeIfAbsent(danlon, k -> new ArrayList<>())
                    .add(new Holder(useruuid, fullName(useruuid), currentStatus(useruuid)));
        }
        List<DuplicateDanlon> result = new ArrayList<>();
        byNumber.forEach((danlon, holders) -> {
            if (holders.size() > 1) result.add(new DuplicateDanlon(danlon, holders));
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<NonConformingValue> findNonConforming() {
        // '0', empty, or not matching T#### / plain numeric.
        List<Object[]> rows = em.createNativeQuery(
                "SELECT h.useruuid, h.danlon FROM user_danlon_history h " +
                "WHERE h.danlon = '0' OR h.danlon = '' OR h.danlon NOT REGEXP '^T?[0-9]+$' " +
                "ORDER BY h.danlon")
                .getResultList();
        List<NonConformingValue> result = new ArrayList<>();
        for (Object[] r : rows) {
            String useruuid = (String) r[0];
            String danlon = (String) r[1];
            String reason = "0".equals(danlon) ? "placeholder '0' (imported by V110)"
                    : (danlon == null || danlon.isEmpty()) ? "empty value"
                    : "non-conforming format (expected T#### or numeric)";
            result.add(new NonConformingValue(useruuid, fullName(useruuid), danlon, reason));
        }
        return result;
    }

    private List<MissingIdActive> findMissingIdActives() {
        LocalDate today = LocalDate.now();
        // Latest status per user as-of today (JPQL via entity — no native userstatus column names).
        List<UserStatus> latest = em.createQuery(
                "SELECT s FROM UserStatus s WHERE s.statusdate <= :today " +
                "AND s.statusdate = (SELECT MAX(s2.statusdate) FROM UserStatus s2 " +
                "                    WHERE s2.useruuid = s.useruuid AND s2.statusdate <= :today)",
                UserStatus.class).setParameter("today", today).getResultList();

        // A user is "active" if ANY of their latest-dated statuses is non-terminated/preboarding.
        Map<String, UserStatus> activeByUser = new LinkedHashMap<>();
        for (UserStatus s : latest) {
            if (s.getStatus() == StatusType.TERMINATED || s.getStatus() == StatusType.PREBOARDING) continue;
            activeByUser.putIfAbsent(s.getUseruuid(), s);
        }

        List<MissingIdActive> result = new ArrayList<>();
        for (Map.Entry<String, UserStatus> e : activeByUser.entrySet()) {
            String useruuid = e.getKey();
            if (UserDanlonHistory.findDanlonAsOf(useruuid, today) != null) continue; // has an OPEN id
            UserStatus s = e.getValue();
            String companyUuid = s.getCompany() != null ? s.getCompany().getUuid() : null;
            result.add(new MissingIdActive(useruuid, fullName(useruuid), companyUuid, s.getStatusdate()));
        }
        return result;
    }

    private String fullName(String useruuid) {
        User u = User.findById(useruuid);
        return u != null ? u.getFullname() : useruuid;
    }

    private String currentStatus(String useruuid) {
        UserStatus s = UserStatus.<UserStatus>find(
                "useruuid = ?1 and statusdate <= ?2 order by statusdate desc", useruuid, LocalDate.now())
                .firstResult();
        return s != null ? s.getStatus().name() : "UNKNOWN";
    }
}
