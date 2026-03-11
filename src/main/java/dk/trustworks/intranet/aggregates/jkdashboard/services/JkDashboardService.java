package dk.trustworks.intranet.aggregates.jkdashboard.services;

import dk.trustworks.intranet.aggregates.jkdashboard.dto.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for JK (Junior Consultant / Student) dashboard analytics.
 * <p>
 * Read-only aggregate service that queries existing tables ({@code work_full},
 * {@code invoiceitems}, {@code invoices}, {@code salary}, {@code userstatus},
 * {@code user}, {@code client}) to compute billing traceability, revenue leakage,
 * profitability, and team composition metrics.
 * <p>
 * All queries use {@code setParameter()} — no string concatenation of user input.
 */
@JBossLog
@ApplicationScoped
public class JkDashboardService {

    private static final String SALARY_TASK_UUID = "a7314f77-5e03-4f56-8b1c-0562e601f22f";
    private static final String INTERNAL_CLIENT_UUID = "40c93307-1dfa-405a-8211-37cbda75318b";
    private static final double OVERHEAD_PER_JK_PER_MONTH = 3000.0;
    private static final double FULLY_BILLED_THRESHOLD = 0.9;

    @Inject
    EntityManager em;

    // ─── Date conversion helper ─────────────────────────────────────────

    /**
     * Converts a native query date result to LocalDate, handling both
     * java.time.LocalDate (Quarkus 3.32+ / Hibernate 6.6+) and legacy java.sql.Date.
     */
    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate ld) return ld;
        if (value instanceof java.sql.Date sd) return sd.toLocalDate();
        throw new IllegalArgumentException("Unexpected date type: " + (value == null ? "null" : value.getClass().getName()));
    }

    // ─── Shared lookup helpers ───────────────────────────────────────────

    /**
     * Returns all user UUIDs that have ever had a STUDENT userstatus record.
     */
    List<String> findAllEverStudentUuids() {
        @SuppressWarnings("unchecked")
        List<String> uuids = em.createNativeQuery("""
                SELECT DISTINCT us.useruuid
                FROM userstatus us
                WHERE us.type = 'STUDENT'
                """)
                .getResultList();
        return uuids;
    }

    /**
     * For each ever-student JK, determines the last date they were a STUDENT.
     * <p>
     * The "student tenure end date" is the date of the first non-STUDENT status record
     * that follows the JK's last STUDENT record. If the JK is still a STUDENT, returns
     * the fiscal year end date or today (whichever is earlier) as an open-ended upper bound.
     * <p>
     * Only work_full rows with {@code registered < studentTenureEndDate} should be included
     * in JK analytics. This prevents post-conversion consultant work from inflating JK metrics.
     *
     * @param fiscalYear the fiscal year to cap the end date within
     * @return map of jkUuid to the exclusive end date of their student tenure
     */
    Map<String, LocalDate> getStudentTenureEndDates(int fiscalYear) {
        @SuppressWarnings("unchecked")
        List<Tuple> statusRows = em.createNativeQuery("""
                SELECT us.useruuid, us.type, us.statusdate
                FROM userstatus us
                WHERE us.useruuid IN (SELECT DISTINCT useruuid FROM userstatus WHERE type = 'STUDENT')
                ORDER BY us.useruuid, us.statusdate
                """, Tuple.class)
                .getResultList();

        // Group status transitions by user, in order
        var userStatuses = new LinkedHashMap<String, List<Object[]>>();
        for (Tuple row : statusRows) {
            String uuid = (String) row.get("useruuid");
            String type = (String) row.get("type");
            LocalDate date = toLocalDate(row.get("statusdate"));
            userStatuses.computeIfAbsent(uuid, k -> new ArrayList<>())
                    .add(new Object[]{type, date});
        }

        LocalDate fyEndDate = fyEnd(fiscalYear);
        LocalDate today = LocalDate.now();
        LocalDate cap = fyEndDate.isAfter(today) ? today : fyEndDate;

        var tenureEndDates = new HashMap<String, LocalDate>();
        for (var entry : userStatuses.entrySet()) {
            String jkUuid = entry.getKey();
            var statuses = entry.getValue();

            // Walk through statuses to find the last STUDENT -> non-STUDENT transition.
            // If no transition happened, the JK is still a student.
            LocalDate lastStudentEnd = null;
            boolean lastWasStudent = false;

            for (Object[] s : statuses) {
                String type = (String) s[0];
                LocalDate date = (LocalDate) s[1];

                if ("STUDENT".equals(type)) {
                    lastWasStudent = true;
                } else if (lastWasStudent) {
                    // First non-STUDENT record after being a STUDENT = conversion date
                    lastStudentEnd = date;
                    lastWasStudent = false;
                }
            }

            if (lastWasStudent) {
                // Still a STUDENT — use the cap date
                tenureEndDates.put(jkUuid, cap);
            } else if (lastStudentEnd != null) {
                // Converted — use the conversion date (capped by FY end)
                tenureEndDates.put(jkUuid, lastStudentEnd.isBefore(cap) ? lastStudentEnd : cap);
            }
            // If lastStudentEnd is null and lastWasStudent is false, user had STUDENT
            // records but also non-STUDENT records before — edge case, skip
        }

        return tenureEndDates;
    }

    /**
     * Filters JK client work tuples to only include rows within each JK's student tenure period.
     * A row is included when its month starts before the JK's student tenure end date.
     */
    private List<Tuple> filterWorkByTenure(List<Tuple> workRows, Map<String, LocalDate> tenureEndDates) {
        return workRows.stream()
                .filter(row -> {
                    String jkUuid = (String) row.get("useruuid");
                    LocalDate tenureEnd = tenureEndDates.get(jkUuid);
                    if (tenureEnd == null) return false;
                    // Month key is "YYYY-MM" — the row is within tenure if the 1st of the month < tenureEnd
                    LocalDate monthStart = YearMonth.parse((String) row.get("month")).atDay(1);
                    return monthStart.isBefore(tenureEnd);
                })
                .collect(Collectors.toList());
    }

    /**
     * Filters salary hours map to only include entries within each JK's student tenure period.
     * Keys are "jkUuid|YYYY-MM" — included when the month starts before the JK's tenure end date.
     */
    private Map<String, Double> filterSalaryHoursByTenure(Map<String, Double> salaryHoursMap,
                                                           Map<String, LocalDate> tenureEndDates) {
        var filtered = new HashMap<String, Double>();
        for (var entry : salaryHoursMap.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            String jkUuid = parts[0];
            String monthKey = parts[1];
            LocalDate tenureEnd = tenureEndDates.get(jkUuid);
            if (tenureEnd == null) continue;
            LocalDate monthStart = YearMonth.parse(monthKey).atDay(1);
            if (monthStart.isBefore(tenureEnd)) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    /**
     * Returns true if the given month falls within the JK's student tenure period.
     */
    private boolean isMonthWithinTenure(String jkUuid, String monthKey, Map<String, LocalDate> tenureEndDates) {
        LocalDate tenureEnd = tenureEndDates.get(jkUuid);
        if (tenureEnd == null) return false;
        LocalDate monthStart = YearMonth.parse(monthKey).atDay(1);
        return monthStart.isBefore(tenureEnd);
    }

    /**
     * Returns a map of userUuid -> "lastname, firstname" for the given UUIDs.
     */
    private Map<String, String> loadUserNames(List<String> uuids) {
        if (uuids.isEmpty()) return Map.of();
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT u.uuid, u.firstname, u.lastname
                FROM user u
                WHERE u.uuid IN (:uuids)
                """, Tuple.class)
                .setParameter("uuids", uuids)
                .getResultList();
        var map = new HashMap<String, String>();
        for (Tuple row : rows) {
            String uuid = (String) row.get("uuid");
            String first = row.get("firstname") != null ? ((String) row.get("firstname")).trim() : "";
            String last = row.get("lastname") != null ? ((String) row.get("lastname")).trim() : "";
            map.put(uuid, last + ", " + first);
        }
        return map;
    }

    /**
     * Returns a map of clientUuid -> clientName.
     */
    private Map<String, String> loadClientNames(Set<String> clientUuids) {
        if (clientUuids.isEmpty()) return Map.of();
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT c.uuid, c.name FROM client c WHERE c.uuid IN (:uuids)
                """, Tuple.class)
                .setParameter("uuids", clientUuids)
                .getResultList();
        var map = new HashMap<String, String>();
        for (Tuple row : rows) {
            map.put((String) row.get("uuid"), (String) row.get("name"));
        }
        return map;
    }

    /**
     * Fiscal year date range: July 1 of fiscalYear to July 1 of fiscalYear+1.
     */
    private LocalDate fyStart(int fiscalYear) {
        return LocalDate.of(fiscalYear, 7, 1);
    }

    private LocalDate fyEnd(int fiscalYear) {
        return LocalDate.of(fiscalYear + 1, 7, 1);
    }

    /**
     * Returns the months in a fiscal year as "YYYY-MM" strings (Jul through Jun).
     * For the current FY, caps at the start of the current month.
     */
    private List<String> fyMonthKeys(int fiscalYear) {
        LocalDate start = fyStart(fiscalYear);
        LocalDate end = fyEnd(fiscalYear);
        LocalDate cap = LocalDate.now().withDayOfMonth(1);
        if (end.isAfter(cap)) {
            end = cap;
        }
        var months = new ArrayList<String>();
        LocalDate cursor = start;
        while (cursor.isBefore(end)) {
            months.add(String.format("%04d-%02d", cursor.getYear(), cursor.getMonthValue()));
            cursor = cursor.plusMonths(1);
        }
        return months;
    }

    // ─── Salary rate lookup ──────────────────────────────────────────────

    /**
     * Builds a map of userUuid -> TreeMap(activefrom -> hourlyRate) for point-in-time salary lookup.
     */
    private Map<String, TreeMap<LocalDate, Integer>> loadSalaryRateMap(List<String> jkUuids) {
        if (jkUuids.isEmpty()) return Map.of();
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT s.useruuid, s.activefrom, s.salary
                FROM salary s
                WHERE s.useruuid IN (:uuids)
                  AND s.type = 'HOURLY'
                ORDER BY s.useruuid, s.activefrom
                """, Tuple.class)
                .setParameter("uuids", jkUuids)
                .getResultList();

        var map = new HashMap<String, TreeMap<LocalDate, Integer>>();
        for (Tuple row : rows) {
            String uuid = (String) row.get("useruuid");
            LocalDate from = toLocalDate(row.get("activefrom"));
            int salary = ((Number) row.get("salary")).intValue();
            map.computeIfAbsent(uuid, k -> new TreeMap<>()).put(from, salary);
        }
        return map;
    }

    /**
     * Returns the hourly salary rate for a JK at the end of the given month.
     */
    private int getHourlyRate(Map<String, TreeMap<LocalDate, Integer>> salaryMap, String jkUuid, String monthKey) {
        var rates = salaryMap.get(jkUuid);
        if (rates == null || rates.isEmpty()) return 0;
        YearMonth ym = YearMonth.parse(monthKey);
        LocalDate endOfMonth = ym.atEndOfMonth();
        var entry = rates.floorEntry(endOfMonth);
        return entry != null ? entry.getValue() : 0;
    }

    // ─── Active JK count ─────────────────────────────────────────────────

    /**
     * Count of JKs whose most recent userstatus (by statusdate <= endOfMonth) is STUDENT + (ACTIVE or PREBOARDING).
     */
    private int countActiveJks(LocalDate asOfDate) {
        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(DISTINCT sub.useruuid) FROM (
                    SELECT us.useruuid, us.type, us.status
                    FROM userstatus us
                    WHERE us.statusdate <= :asOf
                      AND us.statusdate = (
                          SELECT MAX(us2.statusdate)
                          FROM userstatus us2
                          WHERE us2.useruuid = us.useruuid
                            AND us2.statusdate <= :asOf
                      )
                ) sub
                WHERE sub.type = 'STUDENT'
                  AND sub.status IN ('ACTIVE', 'PREBOARDING')
                """)
                .setParameter("asOf", asOfDate)
                .getSingleResult();
        return count.intValue();
    }

    /**
     * Returns the set of JK UUIDs that are active (STUDENT + ACTIVE/PREBOARDING) at the given date.
     */
    private Set<String> activeJkUuidsAt(LocalDate asOfDate) {
        @SuppressWarnings("unchecked")
        List<String> uuids = em.createNativeQuery("""
                SELECT sub.useruuid FROM (
                    SELECT us.useruuid, us.type, us.status
                    FROM userstatus us
                    WHERE us.statusdate <= :asOf
                      AND us.statusdate = (
                          SELECT MAX(us2.statusdate)
                          FROM userstatus us2
                          WHERE us2.useruuid = us.useruuid
                            AND us2.statusdate <= :asOf
                      )
                ) sub
                WHERE sub.type = 'STUDENT'
                  AND sub.status IN ('ACTIVE', 'PREBOARDING')
                """)
                .setParameter("asOf", asOfDate)
                .getResultList();
        return new HashSet<>(uuids);
    }

    // ─── JK Client Work ──────────────────────────────────────────────────

    /**
     * JK client work grouped by (useruuid, clientuuid, projectuuid, month).
     */
    private List<Tuple> queryJkClientWork(List<String> jkUuids, int fiscalYear) {
        if (jkUuids.isEmpty()) return List.of();
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT wf.useruuid, wf.clientuuid, wf.projectuuid,
                       DATE_FORMAT(wf.registered, '%Y-%m') AS month,
                       SUM(wf.workduration) AS registered_hours,
                       AVG(wf.rate) AS avg_rate,
                       MAX(CASE WHEN wf.workas IS NOT NULL AND wf.workas != '' AND wf.workas != wf.useruuid
                           THEN 1 ELSE 0 END) AS has_help_colleague
                FROM work_full wf
                WHERE wf.useruuid IN (:jkUuids)
                  AND wf.taskuuid != :salaryTask
                  AND wf.clientuuid != :internalClient
                  AND wf.registered >= :fyStart AND wf.registered < :fyEnd
                GROUP BY wf.useruuid, wf.clientuuid, wf.projectuuid, DATE_FORMAT(wf.registered, '%Y-%m')
                """, Tuple.class)
                .setParameter("jkUuids", jkUuids)
                .setParameter("salaryTask", SALARY_TASK_UUID)
                .setParameter("internalClient", INTERNAL_CLIENT_UUID)
                .setParameter("fyStart", fyStart(fiscalYear))
                .setParameter("fyEnd", fyEnd(fiscalYear))
                .getResultList();
        return rows;
    }

    /**
     * Fetches the workas UUIDs for JK work entries (help colleague detection).
     * Returns Map<"jkUuid|projectUuid|month" -> Set<workasUuid>>.
     */
    private Map<String, Set<String>> queryJkWorkasMap(List<String> jkUuids, int fiscalYear) {
        if (jkUuids.isEmpty()) return Map.of();
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT wf.useruuid, wf.projectuuid,
                       DATE_FORMAT(wf.registered, '%Y-%m') AS month,
                       wf.workas
                FROM work_full wf
                WHERE wf.useruuid IN (:jkUuids)
                  AND wf.taskuuid != :salaryTask
                  AND wf.clientuuid != :internalClient
                  AND wf.workas IS NOT NULL AND wf.workas != '' AND wf.workas != wf.useruuid
                  AND wf.registered >= :fyStart AND wf.registered < :fyEnd
                """, Tuple.class)
                .setParameter("jkUuids", jkUuids)
                .setParameter("salaryTask", SALARY_TASK_UUID)
                .setParameter("internalClient", INTERNAL_CLIENT_UUID)
                .setParameter("fyStart", fyStart(fiscalYear))
                .setParameter("fyEnd", fyEnd(fiscalYear))
                .getResultList();

        var map = new HashMap<String, Set<String>>();
        for (Tuple row : rows) {
            String key = row.get("useruuid") + "|" + row.get("projectuuid") + "|" + row.get("month");
            String workas = (String) row.get("workas");
            map.computeIfAbsent(key, k -> new HashSet<>()).add(workas);
        }
        return map;
    }

    // ─── JK Invoiced Hours ───────────────────────────────────────────────

    /**
     * JK invoiced hours grouped by (consultantuuid, projectuuid, month).
     * CREDIT_NOTE hours are negative.
     */
    private List<Tuple> queryJkInvoicedHours(List<String> jkUuids, int fiscalYear) {
        if (jkUuids.isEmpty()) return List.of();
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT ii.consultantuuid, i.projectuuid,
                       DATE_FORMAT(i.invoicedate, '%Y-%m') AS month,
                       SUM(CASE WHEN i.type = 'CREDIT_NOTE' THEN -ii.hours ELSE ii.hours END) AS invoiced_hours,
                       AVG(ii.rate) AS invoice_rate
                FROM invoiceitems ii
                JOIN invoices i ON i.uuid = ii.invoiceuuid
                WHERE ii.consultantuuid IN (:jkUuids)
                  AND i.type IN ('INVOICE', 'CREDIT_NOTE')
                  AND i.invoicedate >= :fyStart AND i.invoicedate < :fyEnd
                GROUP BY ii.consultantuuid, i.projectuuid,
                         DATE_FORMAT(i.invoicedate, '%Y-%m')
                """, Tuple.class)
                .setParameter("jkUuids", jkUuids)
                .setParameter("fyStart", fyStart(fiscalYear))
                .setParameter("fyEnd", fyEnd(fiscalYear))
                .getResultList();
        return rows;
    }

    /**
     * JK actual revenue: SUM(hours * rate) with credit notes as negative.
     */
    private Map<String, Double> queryJkActualRevenue(List<String> jkUuids, int fiscalYear) {
        if (jkUuids.isEmpty()) return Map.of();
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT ii.consultantuuid, i.projectuuid,
                       DATE_FORMAT(i.invoicedate, '%Y-%m') AS month,
                       SUM(CASE WHEN i.type = 'CREDIT_NOTE' THEN -(ii.hours * ii.rate) ELSE (ii.hours * ii.rate) END) AS revenue
                FROM invoiceitems ii
                JOIN invoices i ON i.uuid = ii.invoiceuuid
                WHERE ii.consultantuuid IN (:jkUuids)
                  AND i.type IN ('INVOICE', 'CREDIT_NOTE')
                  AND i.invoicedate >= :fyStart AND i.invoicedate < :fyEnd
                GROUP BY ii.consultantuuid, i.projectuuid,
                         DATE_FORMAT(i.invoicedate, '%Y-%m')
                """, Tuple.class)
                .setParameter("jkUuids", jkUuids)
                .setParameter("fyStart", fyStart(fiscalYear))
                .setParameter("fyEnd", fyEnd(fiscalYear))
                .getResultList();

        var map = new HashMap<String, Double>();
        for (Tuple row : rows) {
            String key = row.get("consultantuuid") + "|" + row.get("projectuuid") + "|" + row.get("month");
            double rev = ((Number) row.get("revenue")).doubleValue();
            map.merge(key, rev, Double::sum);
        }
        return map;
    }

    // ─── Regular Consultant Data (for merge detection) ───────────────────

    /**
     * Regular consultant registered hours on projects where JKs have worked.
     * Returns Map<"consultantUuid|projectUuid|month" -> registeredHours>.
     */
    private Map<String, Double> queryRegularConsultantWork(Set<String> projectUuids, List<String> jkUuids, int fiscalYear) {
        if (projectUuids.isEmpty()) return Map.of();
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT wf.useruuid, wf.projectuuid,
                       DATE_FORMAT(wf.registered, '%Y-%m') AS month,
                       SUM(wf.workduration) AS registered_hours
                FROM work_full wf
                WHERE wf.projectuuid IN (:projectUuids)
                  AND wf.useruuid NOT IN (:jkUuids)
                  AND wf.taskuuid != :salaryTask
                  AND wf.clientuuid != :internalClient
                  AND (wf.workas IS NULL OR wf.workas = '' OR wf.workas = wf.useruuid)
                  AND wf.registered >= :fyStart AND wf.registered < :fyEnd
                GROUP BY wf.useruuid, wf.projectuuid, DATE_FORMAT(wf.registered, '%Y-%m')
                """, Tuple.class)
                .setParameter("projectUuids", projectUuids)
                .setParameter("jkUuids", jkUuids)
                .setParameter("salaryTask", SALARY_TASK_UUID)
                .setParameter("internalClient", INTERNAL_CLIENT_UUID)
                .setParameter("fyStart", fyStart(fiscalYear))
                .setParameter("fyEnd", fyEnd(fiscalYear))
                .getResultList();

        var map = new HashMap<String, Double>();
        for (Tuple row : rows) {
            String key = row.get("useruuid") + "|" + row.get("projectuuid") + "|" + row.get("month");
            map.put(key, ((Number) row.get("registered_hours")).doubleValue());
        }
        return map;
    }

    /**
     * Regular consultant invoiced hours on projects where JKs have worked.
     * Returns Map<"consultantUuid|projectUuid|month" -> (invoicedHours, invoiceRate)>.
     */
    private Map<String, double[]> queryRegularConsultantInvoiced(Set<String> projectUuids, List<String> jkUuids, int fiscalYear) {
        if (projectUuids.isEmpty()) return Map.of();
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT ii.consultantuuid, i.projectuuid,
                       DATE_FORMAT(i.invoicedate, '%Y-%m') AS month,
                       SUM(CASE WHEN i.type = 'CREDIT_NOTE' THEN -ii.hours ELSE ii.hours END) AS invoiced_hours,
                       AVG(ii.rate) AS invoice_rate
                FROM invoiceitems ii
                JOIN invoices i ON i.uuid = ii.invoiceuuid
                WHERE i.projectuuid IN (:projectUuids)
                  AND ii.consultantuuid NOT IN (:jkUuids)
                  AND i.type IN ('INVOICE', 'CREDIT_NOTE')
                  AND i.invoicedate >= :fyStart AND i.invoicedate < :fyEnd
                GROUP BY ii.consultantuuid, i.projectuuid,
                         DATE_FORMAT(i.invoicedate, '%Y-%m')
                """, Tuple.class)
                .setParameter("projectUuids", projectUuids)
                .setParameter("jkUuids", jkUuids)
                .setParameter("fyStart", fyStart(fiscalYear))
                .setParameter("fyEnd", fyEnd(fiscalYear))
                .getResultList();

        var map = new HashMap<String, double[]>();
        for (Tuple row : rows) {
            String key = row.get("consultantuuid") + "|" + row.get("projectuuid") + "|" + row.get("month");
            double hours = ((Number) row.get("invoiced_hours")).doubleValue();
            double rate = row.get("invoice_rate") != null ? ((Number) row.get("invoice_rate")).doubleValue() : 0;
            map.put(key, new double[]{hours, rate});
        }
        return map;
    }

    /**
     * Finds all non-STUDENT consultants who have worked on the given projects.
     * Returns Map<"projectUuid|month" -> Set<consultantUuid>>.
     */
    private Map<String, Set<String>> queryRegularConsultantsOnProjects(Set<String> projectUuids, List<String> jkUuids, int fiscalYear) {
        if (projectUuids.isEmpty()) return Map.of();
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT wf.useruuid, wf.projectuuid,
                       DATE_FORMAT(wf.registered, '%Y-%m') AS month
                FROM work_full wf
                WHERE wf.projectuuid IN (:projectUuids)
                  AND wf.useruuid NOT IN (:jkUuids)
                  AND wf.taskuuid != :salaryTask
                  AND wf.clientuuid != :internalClient
                  AND wf.registered >= :fyStart AND wf.registered < :fyEnd
                GROUP BY wf.useruuid, wf.projectuuid, DATE_FORMAT(wf.registered, '%Y-%m')
                """, Tuple.class)
                .setParameter("projectUuids", projectUuids)
                .setParameter("jkUuids", jkUuids)
                .setParameter("salaryTask", SALARY_TASK_UUID)
                .setParameter("internalClient", INTERNAL_CLIENT_UUID)
                .setParameter("fyStart", fyStart(fiscalYear))
                .setParameter("fyEnd", fyEnd(fiscalYear))
                .getResultList();

        var map = new HashMap<String, Set<String>>();
        for (Tuple row : rows) {
            String key = row.get("projectuuid") + "|" + row.get("month");
            map.computeIfAbsent(key, k -> new HashSet<>()).add((String) row.get("useruuid"));
        }
        return map;
    }

    // ─── Salary Task Hours ───────────────────────────────────────────────

    /**
     * JK salary task hours grouped by (useruuid, month).
     */
    private Map<String, Double> queryJkSalaryHours(List<String> jkUuids, int fiscalYear) {
        if (jkUuids.isEmpty()) return Map.of();
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT wf.useruuid,
                       DATE_FORMAT(wf.registered, '%Y-%m') AS month,
                       SUM(wf.workduration) AS salary_hours
                FROM work_full wf
                WHERE wf.useruuid IN (:jkUuids)
                  AND wf.taskuuid = :salaryTask
                  AND wf.registered >= :fyStart AND wf.registered < :fyEnd
                GROUP BY wf.useruuid, DATE_FORMAT(wf.registered, '%Y-%m')
                """, Tuple.class)
                .setParameter("jkUuids", jkUuids)
                .setParameter("salaryTask", SALARY_TASK_UUID)
                .setParameter("fyStart", fyStart(fiscalYear))
                .setParameter("fyEnd", fyEnd(fiscalYear))
                .getResultList();

        var map = new HashMap<String, Double>();
        for (Tuple row : rows) {
            String key = row.get("useruuid") + "|" + row.get("month");
            map.put(key, ((Number) row.get("salary_hours")).doubleValue());
        }
        return map;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  BILLING TRACEABILITY — core classification engine
    // ═══════════════════════════════════════════════════════════════════════

    public List<BillingTraceabilityRow> getBillingTraceability(int fiscalYear) {
        return getBillingTraceability(fiscalYear, null);
    }

    List<BillingTraceabilityRow> getBillingTraceability(int fiscalYear,
                                                        Map<String, LocalDate> precomputedTenure) {
        var jkUuids = findAllEverStudentUuids();
        if (jkUuids.isEmpty()) return List.of();

        var tenureEndDates = precomputedTenure != null ? precomputedTenure : getStudentTenureEndDates(fiscalYear);

        // Pre-fetch all data, then filter by student tenure period
        var jkWork = filterWorkByTenure(queryJkClientWork(jkUuids, fiscalYear), tenureEndDates);
        var jkInvoiced = queryJkInvoicedHours(jkUuids, fiscalYear);
        var workasMap = queryJkWorkasMap(jkUuids, fiscalYear);

        // Build JK invoiced lookup: "consultantUuid|projectUuid|month" -> (invoicedHours, invoiceRate)
        var jkInvoicedMap = new HashMap<String, double[]>();
        for (Tuple row : jkInvoiced) {
            String key = row.get("consultantuuid") + "|" + row.get("projectuuid") + "|" + row.get("month");
            double hours = ((Number) row.get("invoiced_hours")).doubleValue();
            double rate = row.get("invoice_rate") != null ? ((Number) row.get("invoice_rate")).doubleValue() : 0;
            jkInvoicedMap.put(key, new double[]{hours, rate});
        }

        // Collect all project UUIDs for merge detection
        Set<String> projectUuids = new HashSet<>();
        for (Tuple row : jkWork) {
            projectUuids.add((String) row.get("projectuuid"));
        }

        // Pre-fetch regular consultant data for merge detection
        var regWork = queryRegularConsultantWork(projectUuids, jkUuids, fiscalYear);
        var regInvoiced = queryRegularConsultantInvoiced(projectUuids, jkUuids, fiscalYear);
        var regOnProjects = queryRegularConsultantsOnProjects(projectUuids, jkUuids, fiscalYear);

        // Load name lookups
        Set<String> allUserUuids = new HashSet<>(jkUuids);
        regWork.keySet().forEach(k -> allUserUuids.add(k.split("\\|")[0]));
        regInvoiced.keySet().forEach(k -> allUserUuids.add(k.split("\\|")[0]));
        var userNames = loadUserNames(new ArrayList<>(allUserUuids));

        Set<String> allClientUuids = new HashSet<>();
        for (Tuple row : jkWork) {
            allClientUuids.add((String) row.get("clientuuid"));
        }
        var clientNames = loadClientNames(allClientUuids);

        // Classify each JK-client-project-month combination
        var result = new ArrayList<BillingTraceabilityRow>();
        for (Tuple row : jkWork) {
            String jkUuid = (String) row.get("useruuid");
            String clientUuid = (String) row.get("clientuuid");
            String projectUuid = (String) row.get("projectuuid");
            String month = (String) row.get("month");
            double regHours = ((Number) row.get("registered_hours")).doubleValue();
            double avgRate = ((Number) row.get("avg_rate")).doubleValue();
            int hasHelp = ((Number) row.get("has_help_colleague")).intValue();

            String invoiceKey = jkUuid + "|" + projectUuid + "|" + month;
            double[] invoiceData = jkInvoicedMap.get(invoiceKey);
            double invoicedHours = invoiceData != null ? invoiceData[0] : 0;

            var btRow = new BillingTraceabilityRow();
            btRow.setJkUuid(jkUuid);
            btRow.setJkName(userNames.getOrDefault(jkUuid, jkUuid));
            btRow.setClientUuid(clientUuid);
            btRow.setClientName(clientNames.getOrDefault(clientUuid, clientUuid));
            btRow.setProjectUuid(projectUuid);
            btRow.setMonth(month);
            btRow.setRegisteredHours(regHours);
            btRow.setInvoicedHours(Math.max(invoicedHours, 0));
            btRow.setAvgRate(avgRate);

            // Classification algorithm (Section 4.2 of spec)
            if (invoiceData != null && invoicedHours > 0) {
                // JK has own invoice items
                if (invoicedHours >= FULLY_BILLED_THRESHOLD * regHours) {
                    btRow.setScenario(BillingScenario.FULLY_BILLED);
                } else {
                    btRow.setScenario(BillingScenario.PARTIALLY_BILLED);
                }
            } else {
                // No JK invoice items — check for merge
                boolean isHelp = hasHelp == 1;
                MergeDetail bestMerge = null;
                double bestSurplus = 0;

                if (isHelp) {
                    // Help colleague: check the workas consultant(s)
                    String workasKey = jkUuid + "|" + projectUuid + "|" + month;
                    Set<String> workasUuids = workasMap.getOrDefault(workasKey, Set.of());
                    for (String workasUuid : workasUuids) {
                        var merge = detectMerge(workasUuid, projectUuid, month, regWork, regInvoiced, userNames);
                        if (merge != null && merge.getSurplusHours() > bestSurplus) {
                            bestMerge = merge;
                            bestSurplus = merge.getSurplusHours();
                        }
                    }
                    if (bestMerge != null) {
                        btRow.setScenario(BillingScenario.HELP_MERGED);
                        btRow.setMergeDetails(bestMerge);
                        btRow.setMergeConfidence(computeConfidence(bestSurplus, regHours));
                    } else {
                        btRow.setScenario(BillingScenario.HELP_NOT_BILLED);
                    }
                } else {
                    // Not help colleague: check all regular consultants on same project/month
                    String projMonthKey = projectUuid + "|" + month;
                    Set<String> regulars = regOnProjects.getOrDefault(projMonthKey, Set.of());
                    for (String regUuid : regulars) {
                        var merge = detectMerge(regUuid, projectUuid, month, regWork, regInvoiced, userNames);
                        if (merge != null && merge.getSurplusHours() > bestSurplus) {
                            bestMerge = merge;
                            bestSurplus = merge.getSurplusHours();
                        }
                    }
                    if (bestMerge != null) {
                        btRow.setScenario(BillingScenario.POSSIBLY_MERGED);
                        btRow.setMergeDetails(bestMerge);
                        btRow.setMergeConfidence(computeConfidence(bestSurplus, regHours));
                    } else {
                        btRow.setScenario(BillingScenario.NOT_BILLED);
                    }
                }
            }

            result.add(btRow);
        }

        // Sort: month DESC, then JK name ASC
        result.sort(Comparator.comparing(BillingTraceabilityRow::getMonth).reversed()
                .thenComparing(BillingTraceabilityRow::getJkName));

        return result;
    }

    /**
     * Detect merge for a regular consultant on a given project/month.
     * Returns MergeDetail if invoiced > registered (surplus exists), null otherwise.
     */
    private MergeDetail detectMerge(String consultantUuid, String projectUuid, String month,
                                     Map<String, Double> regWork,
                                     Map<String, double[]> regInvoiced,
                                     Map<String, String> userNames) {
        String key = consultantUuid + "|" + projectUuid + "|" + month;
        double regHours = regWork.getOrDefault(key, 0.0);
        double[] invData = regInvoiced.get(key);
        if (invData == null) return null;
        double invHours = invData[0];
        if (invHours <= regHours) return null;

        double surplus = invHours - regHours;
        return new MergeDetail(
                consultantUuid,
                userNames.getOrDefault(consultantUuid, consultantUuid),
                regHours,
                invHours,
                surplus
        );
    }

    private MergeConfidence computeConfidence(double surplus, double jkRegisteredHours) {
        if (jkRegisteredHours <= 0) return MergeConfidence.LOW;
        double ratio = surplus / jkRegisteredHours;
        if (ratio >= 0.8) return MergeConfidence.HIGH;
        if (ratio >= 0.3) return MergeConfidence.MEDIUM;
        return MergeConfidence.LOW;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  REVENUE LEAKAGE
    // ═══════════════════════════════════════════════════════════════════════

    public RevenueLeakageResponse getRevenueLeakage(int fiscalYear) {
        return getRevenueLeakage(fiscalYear, null, null, null);
    }

    RevenueLeakageResponse getRevenueLeakage(int fiscalYear,
                                              List<BillingTraceabilityRow> precomputedTraceability,
                                              List<String> precomputedJkUuids,
                                              Map<String, LocalDate> precomputedTenure) {
        var traceability = precomputedTraceability != null ? precomputedTraceability : getBillingTraceability(fiscalYear, precomputedTenure);
        var jkUuids = precomputedJkUuids != null ? precomputedJkUuids : findAllEverStudentUuids();
        var jkRevenue = queryJkActualRevenue(jkUuids, fiscalYear);

        // Group by month
        var monthMap = new TreeMap<String, double[]>();
        // [directlyBilled, merged, trulyLost, uncertain, totalPotential]

        for (BillingTraceabilityRow row : traceability) {
            double potentialRev = row.getRegisteredHours() * row.getAvgRate();
            double[] bucket = monthMap.computeIfAbsent(row.getMonth(), k -> new double[5]);
            bucket[4] += potentialRev; // totalPotential

            String revenueKey = row.getJkUuid() + "|" + row.getProjectUuid() + "|" + row.getMonth();
            double directRev = jkRevenue.getOrDefault(revenueKey, 0.0);

            switch (row.getScenario()) {
                case FULLY_BILLED, PARTIALLY_BILLED -> bucket[0] += Math.max(directRev, 0);
                case POSSIBLY_MERGED, HELP_MERGED -> {
                    // Revenue attributed to merge
                    if (row.getMergeDetails() != null) {
                        // Use surplus hours * regular consultant's invoice rate (approximation)
                        double surplusRev = row.getMergeDetails().getSurplusHours() *
                                (row.getMergeDetails().getRegularInvoicedHours() > 0 ?
                                        (row.getAvgRate()) : row.getAvgRate());
                        // Cap merged revenue at potential revenue for this row
                        bucket[1] += Math.min(surplusRev, potentialRev);
                    } else {
                        bucket[3] += potentialRev; // uncertain
                    }
                }
                case NOT_BILLED -> bucket[2] += potentialRev; // truly lost
                case HELP_NOT_BILLED -> bucket[2] += potentialRev; // truly lost
            }
        }

        double totalDirectly = 0, totalMerged = 0, totalLost = 0, totalUncertain = 0, totalPotential = 0;
        var months = new ArrayList<RevenueLeakageMonth>();
        for (var entry : monthMap.entrySet()) {
            double[] b = entry.getValue();
            months.add(new RevenueLeakageMonth(entry.getKey(), b[0], b[1], b[2], b[3], b[4]));
            totalDirectly += b[0];
            totalMerged += b[1];
            totalLost += b[2];
            totalUncertain += b[3];
            totalPotential += b[4];
        }

        double coveragePercent = totalPotential > 0
                ? ((totalDirectly + totalMerged) / totalPotential) * 100.0
                : 0.0;

        var totals = new RevenueLeakageTotals(totalDirectly, totalMerged, totalLost, totalUncertain, coveragePercent);
        return new RevenueLeakageResponse(months, totals);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  KPIs
    // ═══════════════════════════════════════════════════════════════════════

    public JkKpiResponse getKpis(int fiscalYear) {
        var kpi = computeKpis(fiscalYear);

        // Compute previous FY for YoY comparison
        JkKpiResponse prevKpi = null;
        try {
            prevKpi = computeKpis(fiscalYear - 1);
        } catch (Exception e) {
            log.debugf("No previous FY data for %d: %s", fiscalYear - 1, e.getMessage());
        }
        kpi.setPreviousFy(prevKpi);
        return kpi;
    }

    private JkKpiResponse computeKpis(int fiscalYear) {
        return computeKpis(fiscalYear, null, null, null);
    }

    JkKpiResponse computeKpis(int fiscalYear, RevenueLeakageResponse precomputedLeakage,
                               Map<LocalDate, Integer> activeJkCache,
                               Map<String, LocalDate> precomputedTenure) {
        var jkUuids = findAllEverStudentUuids();
        var tenureEndDates = precomputedTenure != null ? precomputedTenure : getStudentTenureEndDates(fiscalYear);
        var salaryHoursMap = filterSalaryHoursByTenure(queryJkSalaryHours(jkUuids, fiscalYear), tenureEndDates);
        var salaryRateMap = loadSalaryRateMap(jkUuids);
        var fyMonths = fyMonthKeys(fiscalYear);

        // 1. Active JK count (at end of latest complete month)
        LocalDate latestMonth = fyMonths.isEmpty() ? fyStart(fiscalYear)
                : YearMonth.parse(fyMonths.get(fyMonths.size() - 1)).atEndOfMonth();
        int activeJkCount = countActiveJksCached(latestMonth, activeJkCache);

        // 2. Total salary cost (only for months within student tenure)
        double totalSalaryCost = 0;
        for (String jkUuid : jkUuids) {
            for (String month : fyMonths) {
                String key = jkUuid + "|" + month;
                double salaryHrs = salaryHoursMap.getOrDefault(key, 0.0);
                int rate = getHourlyRate(salaryRateMap, jkUuid, month);
                totalSalaryCost += salaryHrs * rate;
            }
        }

        // 3. Total client hours (filtered by tenure)
        var jkWork = filterWorkByTenure(queryJkClientWork(jkUuids, fiscalYear), tenureEndDates);
        double totalClientHours = 0;
        for (Tuple row : jkWork) {
            totalClientHours += ((Number) row.get("registered_hours")).doubleValue();
        }

        // 4-6. Billing coverage, revenue leakage (derive from revenue leakage response)
        var leakage = precomputedLeakage != null ? precomputedLeakage : getRevenueLeakage(fiscalYear);
        var totals = leakage.getFyTotals();
        double billingCoverage = totals.getBillingCoveragePercent();
        double directAndMergedRevenue = totals.getDirectlyBilled() + totals.getMerged();
        double revenuePerHour = totalClientHours > 0 ? directAndMergedRevenue / totalClientHours : 0;
        double revenueLeakageDkk = totals.getTrulyLost() + totals.getUncertain();

        // 7. Unassigned JKs: JKs with salary_hours > 0 and client_hours = 0 for >1 month
        var jkClientHoursPerMonth = new HashMap<String, Map<String, Double>>(); // jkUuid -> month -> clientHours
        for (Tuple row : jkWork) {
            String jkUuid = (String) row.get("useruuid");
            String month = (String) row.get("month");
            double hrs = ((Number) row.get("registered_hours")).doubleValue();
            jkClientHoursPerMonth.computeIfAbsent(jkUuid, k -> new HashMap<>())
                    .merge(month, hrs, Double::sum);
        }
        int unassignedCount = 0;
        for (String jkUuid : jkUuids) {
            int monthsWithSalaryNoClient = 0;
            for (String month : fyMonths) {
                String salKey = jkUuid + "|" + month;
                double salHrs = salaryHoursMap.getOrDefault(salKey, 0.0);
                double cliHrs = jkClientHoursPerMonth.getOrDefault(jkUuid, Map.of())
                        .getOrDefault(month, 0.0);
                if (salHrs > 0 && cliHrs == 0) {
                    monthsWithSalaryNoClient++;
                }
            }
            if (monthsWithSalaryNoClient > 1) {
                unassignedCount++;
            }
        }

        // 8. Team growth
        int startCount = countActiveJksCached(fyStart(fiscalYear), activeJkCache);
        double teamGrowthPct = startCount > 0
                ? ((double) (activeJkCount - startCount) / startCount) * 100.0
                : (activeJkCount > 0 ? 100.0 : 0.0);

        var kpi = new JkKpiResponse();
        kpi.setActiveJkCount(activeJkCount);
        kpi.setTotalSalaryCost(totalSalaryCost);
        kpi.setTotalClientHours(totalClientHours);
        kpi.setBillingCoveragePercent(billingCoverage);
        kpi.setRevenuePerJkHour(revenuePerHour);
        kpi.setRevenueLeakageDkk(revenueLeakageDkk);
        kpi.setUnassignedJkCount(unassignedCount);
        kpi.setTeamGrowthPercent(teamGrowthPct);
        return kpi;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PROFITABILITY
    // ═══════════════════════════════════════════════════════════════════════

    public JkProfitabilityResponse getProfitability(int fiscalYear) {
        return getProfitability(fiscalYear, null, null, null);
    }

    JkProfitabilityResponse getProfitability(int fiscalYear,
                                              RevenueLeakageResponse precomputedLeakage,
                                              Map<LocalDate, Integer> activeJkCache,
                                              Map<String, LocalDate> precomputedTenure) {
        var jkUuids = findAllEverStudentUuids();
        var tenureEndDates = precomputedTenure != null ? precomputedTenure : getStudentTenureEndDates(fiscalYear);
        var salaryHoursMap = filterSalaryHoursByTenure(queryJkSalaryHours(jkUuids, fiscalYear), tenureEndDates);
        var salaryRateMap = loadSalaryRateMap(jkUuids);
        var fyMonths = fyMonthKeys(fiscalYear);
        var leakage = precomputedLeakage != null ? precomputedLeakage : getRevenueLeakage(fiscalYear);

        // Build leakage month lookup
        var leakageByMonth = new HashMap<String, RevenueLeakageMonth>();
        for (RevenueLeakageMonth m : leakage.getMonths()) {
            leakageByMonth.put(m.getMonth(), m);
        }

        double cumulativePL = 0;
        var months = new ArrayList<JkProfitabilityMonth>();

        for (String month : fyMonths) {
            // Salary cost
            double salaryCost = 0;
            for (String jkUuid : jkUuids) {
                String key = jkUuid + "|" + month;
                double salaryHrs = salaryHoursMap.getOrDefault(key, 0.0);
                int rate = getHourlyRate(salaryRateMap, jkUuid, month);
                salaryCost += salaryHrs * rate;
            }

            // Overhead cost
            YearMonth ym = YearMonth.parse(month);
            int activeCount = countActiveJksCached(ym.atEndOfMonth(), activeJkCache);
            double overheadCost = OVERHEAD_PER_JK_PER_MONTH * activeCount;

            double totalCost = salaryCost + overheadCost;

            // Revenue from leakage data
            var lm = leakageByMonth.get(month);
            double actualRevenue = lm != null ? (lm.getDirectlyBilled() + lm.getMerged()) : 0;
            double potentialRevenue = lm != null ? lm.getTotalPotential() : 0;

            double netPL = actualRevenue - totalCost;
            cumulativePL += netPL;

            months.add(new JkProfitabilityMonth(month, salaryCost, overheadCost, totalCost,
                    actualRevenue, potentialRevenue, netPL, cumulativePL));
        }

        return new JkProfitabilityResponse(months);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  RATE ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════

    public RateAnalysisResponse getRateAnalysis(int fiscalYear) {
        return getRateAnalysis(fiscalYear, null, null);
    }

    RateAnalysisResponse getRateAnalysis(int fiscalYear,
                                          List<BillingTraceabilityRow> precomputedTraceability,
                                          Map<String, LocalDate> precomputedTenure) {
        var jkUuids = findAllEverStudentUuids();
        var tenureEndDates = precomputedTenure != null ? precomputedTenure : getStudentTenureEndDates(fiscalYear);
        var jkWork = filterWorkByTenure(queryJkClientWork(jkUuids, fiscalYear), tenureEndDates);
        var traceability = precomputedTraceability != null ? precomputedTraceability : getBillingTraceability(fiscalYear);

        // Monthly rates (excluding rate <= 1)
        var monthlyData = new TreeMap<String, double[]>();
        // [sumRate, count, minRate, maxRate, totalHours, jkSet hash]
        var monthlyJkSets = new TreeMap<String, Set<String>>();

        for (Tuple row : jkWork) {
            double avgRate = ((Number) row.get("avg_rate")).doubleValue();
            if (avgRate <= 1) continue;
            String month = (String) row.get("month");
            double hours = ((Number) row.get("registered_hours")).doubleValue();
            String jkUuid = (String) row.get("useruuid");

            double[] d = monthlyData.computeIfAbsent(month, k -> new double[]{0, 0, Double.MAX_VALUE, 0, 0});
            d[0] += avgRate * hours; // weighted sum
            d[1] += hours; // total hours for weighting
            d[2] = Math.min(d[2], avgRate);
            d[3] = Math.max(d[3], avgRate);
            d[4] += hours;
            monthlyJkSets.computeIfAbsent(month, k -> new HashSet<>()).add(jkUuid);
        }

        var monthlyRates = new ArrayList<MonthlyRateEntry>();
        for (var entry : monthlyData.entrySet()) {
            double[] d = entry.getValue();
            double avg = d[1] > 0 ? d[0] / d[1] : 0;
            int jkCount = monthlyJkSets.getOrDefault(entry.getKey(), Set.of()).size();
            monthlyRates.add(new MonthlyRateEntry(entry.getKey(), avg, d[2], d[3], jkCount, d[4]));
        }

        // Rate bands
        var bandMap = new TreeMap<String, double[]>(); // band -> [jkClientMonths, billedMonths]
        bandMap.put("<600", new double[2]);
        bandMap.put("600-900", new double[2]);
        bandMap.put("900-1200", new double[2]);
        bandMap.put("1200+", new double[2]);

        // Rate vs billing scatter: group traceability by jk+client
        var jkClientMap = new HashMap<String, double[]>(); // "jkName|clientName" -> [sumRate*hrs, totalHrs, billedHrs]
        var jkClientNames = new HashMap<String, String[]>(); // key -> [jkName, clientName]

        for (BillingTraceabilityRow row : traceability) {
            if (row.getAvgRate() <= 1) continue;
            String key = row.getJkUuid() + "|" + row.getClientUuid();
            double[] d = jkClientMap.computeIfAbsent(key, k -> new double[3]);
            d[0] += row.getAvgRate() * row.getRegisteredHours();
            d[1] += row.getRegisteredHours();
            if (row.getScenario() == BillingScenario.FULLY_BILLED || row.getScenario() == BillingScenario.PARTIALLY_BILLED) {
                d[2] += row.getInvoicedHours();
            } else if (row.getScenario() == BillingScenario.POSSIBLY_MERGED || row.getScenario() == BillingScenario.HELP_MERGED) {
                d[2] += row.getRegisteredHours(); // count merged as billed
            }
            jkClientNames.putIfAbsent(key, new String[]{row.getJkName(), row.getClientName()});

            // Rate band classification
            String band;
            if (row.getAvgRate() < 600) band = "<600";
            else if (row.getAvgRate() < 900) band = "600-900";
            else if (row.getAvgRate() < 1200) band = "900-1200";
            else band = "1200+";

            bandMap.get(band)[0]++;
            if (row.getScenario() == BillingScenario.FULLY_BILLED || row.getScenario() == BillingScenario.PARTIALLY_BILLED
                    || row.getScenario() == BillingScenario.POSSIBLY_MERGED || row.getScenario() == BillingScenario.HELP_MERGED) {
                bandMap.get(band)[1]++;
            }
        }

        var rateBands = new ArrayList<RateBandEntry>();
        for (var entry : bandMap.entrySet()) {
            double[] d = entry.getValue();
            double pct = d[0] > 0 ? (d[1] / d[0]) * 100.0 : 0;
            rateBands.add(new RateBandEntry(entry.getKey(), (int) d[0], pct));
        }

        var rateVsBilling = new ArrayList<RateVsBillingEntry>();
        for (var entry : jkClientMap.entrySet()) {
            double[] d = entry.getValue();
            double avgRate = d[1] > 0 ? d[0] / d[1] : 0;
            double billingPct = d[1] > 0 ? (d[2] / d[1]) * 100.0 : 0;
            String[] names = jkClientNames.get(entry.getKey());
            rateVsBilling.add(new RateVsBillingEntry(names[0], names[1], avgRate, billingPct, d[1]));
        }

        return new RateAnalysisResponse(monthlyRates, rateBands, rateVsBilling);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SALARY ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════

    public SalaryAnalysisResponse getSalaryAnalysis(int fiscalYear) {
        return getSalaryAnalysis(fiscalYear, null);
    }

    SalaryAnalysisResponse getSalaryAnalysis(int fiscalYear, Map<String, LocalDate> precomputedTenure) {
        var jkUuids = findAllEverStudentUuids();
        var tenureEndDates = precomputedTenure != null ? precomputedTenure : getStudentTenureEndDates(fiscalYear);
        var salaryHoursMap = filterSalaryHoursByTenure(queryJkSalaryHours(jkUuids, fiscalYear), tenureEndDates);
        var salaryRateMap = loadSalaryRateMap(jkUuids);
        var fyMonths = fyMonthKeys(fiscalYear);
        var jkWork = filterWorkByTenure(queryJkClientWork(jkUuids, fiscalYear), tenureEndDates);
        var userNames = loadUserNames(jkUuids);

        // Monthly salary aggregation
        var monthlySalary = new ArrayList<MonthlySalaryEntry>();
        for (String month : fyMonths) {
            double totalCost = 0;
            double totalHours = 0;
            YearMonth ym = YearMonth.parse(month);
            int activeCount = countActiveJks(ym.atEndOfMonth());

            for (String jkUuid : jkUuids) {
                String key = jkUuid + "|" + month;
                double hrs = salaryHoursMap.getOrDefault(key, 0.0);
                int rate = getHourlyRate(salaryRateMap, jkUuid, month);
                totalCost += hrs * rate;
                totalHours += hrs;
            }
            monthlySalary.add(new MonthlySalaryEntry(month, totalCost, totalHours, activeCount));
        }

        // Per-JK salary vs client hours
        var jkClientHours = new HashMap<String, Double>();
        for (Tuple row : jkWork) {
            String jkUuid = (String) row.get("useruuid");
            double hrs = ((Number) row.get("registered_hours")).doubleValue();
            jkClientHours.merge(jkUuid, hrs, Double::sum);
        }

        var perJk = new ArrayList<PerJkSalaryVsClientEntry>();
        for (String jkUuid : jkUuids) {
            double totalSalaryHrs = 0;
            double totalSalaryCost = 0;
            int latestRate = 0;
            for (String month : fyMonths) {
                String key = jkUuid + "|" + month;
                double hrs = salaryHoursMap.getOrDefault(key, 0.0);
                int rate = getHourlyRate(salaryRateMap, jkUuid, month);
                totalSalaryHrs += hrs;
                totalSalaryCost += hrs * rate;
                if (rate > 0) latestRate = rate;
            }
            if (totalSalaryHrs == 0 && jkClientHours.getOrDefault(jkUuid, 0.0) == 0) continue;

            double clientHrs = jkClientHours.getOrDefault(jkUuid, 0.0);
            perJk.add(new PerJkSalaryVsClientEntry(jkUuid,
                    userNames.getOrDefault(jkUuid, jkUuid),
                    totalSalaryHrs, clientHrs, latestRate, totalSalaryCost));
        }

        return new SalaryAnalysisResponse(monthlySalary, perJk);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TEAM OVERVIEW
    // ═══════════════════════════════════════════════════════════════════════

    public List<JkTeamMemberSummary> getTeamOverview(int fiscalYear) {
        return getTeamOverview(fiscalYear, null, null, null);
    }

    List<JkTeamMemberSummary> getTeamOverview(int fiscalYear,
                                               List<BillingTraceabilityRow> precomputedTraceability,
                                               RevenueLeakageResponse precomputedLeakage,
                                               Map<String, LocalDate> precomputedTenure) {
        var jkUuids = findAllEverStudentUuids();
        var tenureEndDates = precomputedTenure != null ? precomputedTenure : getStudentTenureEndDates(fiscalYear);
        var salaryHoursMap = filterSalaryHoursByTenure(queryJkSalaryHours(jkUuids, fiscalYear), tenureEndDates);
        var salaryRateMap = loadSalaryRateMap(jkUuids);
        var fyMonths = fyMonthKeys(fiscalYear);
        var jkWork = filterWorkByTenure(queryJkClientWork(jkUuids, fiscalYear), tenureEndDates);
        var traceability = precomputedTraceability != null ? precomputedTraceability : getBillingTraceability(fiscalYear);
        var userNames = loadUserNames(jkUuids);

        // Per-JK client hours by client
        var jkClientHours = new HashMap<String, Double>(); // jkUuid -> total
        var jkClientBreakdown = new HashMap<String, Map<String, Double>>(); // jkUuid -> clientUuid -> hours
        var jkRateSum = new HashMap<String, double[]>(); // jkUuid -> [sumRate*hrs, totalHrs]
        Set<String> allClientUuids = new HashSet<>();

        for (Tuple row : jkWork) {
            String jkUuid = (String) row.get("useruuid");
            String clientUuid = (String) row.get("clientuuid");
            double hrs = ((Number) row.get("registered_hours")).doubleValue();
            double rate = ((Number) row.get("avg_rate")).doubleValue();

            jkClientHours.merge(jkUuid, hrs, Double::sum);
            jkClientBreakdown.computeIfAbsent(jkUuid, k -> new HashMap<>())
                    .merge(clientUuid, hrs, Double::sum);
            allClientUuids.add(clientUuid);

            if (rate > 1) {
                jkRateSum.computeIfAbsent(jkUuid, k -> new double[2]);
                jkRateSum.get(jkUuid)[0] += rate * hrs;
                jkRateSum.get(jkUuid)[1] += hrs;
            }
        }
        var clientNames = loadClientNames(allClientUuids);

        // Per-JK billing %
        var jkBilling = new HashMap<String, double[]>(); // jkUuid -> [billedHrs, totalHrs]
        for (BillingTraceabilityRow row : traceability) {
            double[] d = jkBilling.computeIfAbsent(row.getJkUuid(), k -> new double[2]);
            d[1] += row.getRegisteredHours();
            if (row.getScenario() == BillingScenario.FULLY_BILLED || row.getScenario() == BillingScenario.PARTIALLY_BILLED
                    || row.getScenario() == BillingScenario.POSSIBLY_MERGED || row.getScenario() == BillingScenario.HELP_MERGED) {
                d[0] += row.getRegisteredHours();
            }
        }

        // Per-JK current status + months as student
        @SuppressWarnings("unchecked")
        List<Tuple> statusRows = em.createNativeQuery("""
                SELECT us.useruuid, us.status, us.type, us.statusdate
                FROM userstatus us
                WHERE us.useruuid IN (:jkUuids)
                ORDER BY us.useruuid, us.statusdate
                """, Tuple.class)
                .setParameter("jkUuids", jkUuids)
                .getResultList();

        var currentStatusMap = new HashMap<String, String>(); // jkUuid -> current status
        var firstStudentDate = new HashMap<String, LocalDate>(); // jkUuid -> first STUDENT date
        var lastStudentDate = new HashMap<String, LocalDate>(); // jkUuid -> last date still STUDENT

        for (Tuple row : statusRows) {
            String uuid = (String) row.get("useruuid");
            String status = (String) row.get("status");
            String type = (String) row.get("type");
            LocalDate date = toLocalDate(row.get("statusdate"));

            currentStatusMap.put(uuid, status);
            if ("STUDENT".equals(type)) {
                firstStudentDate.putIfAbsent(uuid, date);
                lastStudentDate.put(uuid, date);
            }
        }

        // Build per-JK P&L (reuse from getPnl computation)
        var pnlMap = new HashMap<String, Double>();
        var leakage = precomputedLeakage != null ? precomputedLeakage
                : getRevenueLeakage(fiscalYear, traceability, jkUuids, precomputedTenure);
        // Simple P&L: (direct+merged revenue - salary cost - overhead)
        // Compute per-JK revenue from traceability (tenure-filtered)
        var jkRevenue = queryJkActualRevenue(jkUuids, fiscalYear);
        for (String jkUuid : jkUuids) {
            double totalRev = 0;
            LocalDate tenureEnd = tenureEndDates.get(jkUuid);
            for (var entry : jkRevenue.entrySet()) {
                if (entry.getKey().startsWith(jkUuid + "|")) {
                    String monthKey = entry.getKey().substring(entry.getKey().lastIndexOf('|') + 1);
                    LocalDate monthStart = YearMonth.parse(monthKey).atDay(1);
                    if (tenureEnd != null && monthStart.isBefore(tenureEnd)) {
                        totalRev += entry.getValue();
                    }
                }
            }
            // Add estimated merged revenue from traceability
            for (BillingTraceabilityRow row : traceability) {
                if (row.getJkUuid().equals(jkUuid) && row.getMergeDetails() != null
                        && (row.getScenario() == BillingScenario.POSSIBLY_MERGED || row.getScenario() == BillingScenario.HELP_MERGED)) {
                    totalRev += Math.min(row.getMergeDetails().getSurplusHours() * row.getAvgRate(),
                            row.getRegisteredHours() * row.getAvgRate());
                }
            }
            double totalSalaryCost = 0;
            int activeMonths = 0;
            for (String month : fyMonths) {
                String key = jkUuid + "|" + month;
                double hrs = salaryHoursMap.getOrDefault(key, 0.0);
                int rate = getHourlyRate(salaryRateMap, jkUuid, month);
                totalSalaryCost += hrs * rate;
                if (hrs > 0) activeMonths++;
            }
            double overhead = OVERHEAD_PER_JK_PER_MONTH * activeMonths;
            pnlMap.put(jkUuid, totalRev - totalSalaryCost - overhead);
        }

        // Build summaries
        var result = new ArrayList<JkTeamMemberSummary>();
        for (String jkUuid : jkUuids) {
            double totalSalaryHrs = 0;
            for (String month : fyMonths) {
                totalSalaryHrs += salaryHoursMap.getOrDefault(jkUuid + "|" + month, 0.0);
            }
            double clientHrs = jkClientHours.getOrDefault(jkUuid, 0.0);

            // Skip JKs with no activity at all in this FY
            if (totalSalaryHrs == 0 && clientHrs == 0) continue;

            double[] billing = jkBilling.getOrDefault(jkUuid, new double[2]);
            double billingPct = billing[1] > 0 ? (billing[0] / billing[1]) * 100.0 : 0;
            double[] rateData = jkRateSum.getOrDefault(jkUuid, new double[2]);
            double avgRate = rateData[1] > 0 ? rateData[0] / rateData[1] : 0;

            // Top 3 clients
            var clientBreakdown = jkClientBreakdown.getOrDefault(jkUuid, Map.of());
            var topClients = clientBreakdown.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(3)
                    .map(e -> clientNames.getOrDefault(e.getKey(), e.getKey()))
                    .collect(Collectors.toList());

            // Months as student
            LocalDate firstDate = firstStudentDate.get(jkUuid);
            int monthsAsStudent = 0;
            if (firstDate != null) {
                LocalDate endDate = LocalDate.now();
                monthsAsStudent = (int) ChronoUnit.MONTHS.between(firstDate, endDate);
            }

            // Unassigned check
            int unassignedMonths = 0;
            for (String month : fyMonths) {
                double salHrs = salaryHoursMap.getOrDefault(jkUuid + "|" + month, 0.0);
                double cliHrs = jkClientBreakdown.getOrDefault(jkUuid, Map.of())
                        .values().stream().mapToDouble(Double::doubleValue).sum();
                // Need monthly client hours — recalculate
                Map<String, Double> monthlyClientHrs = new HashMap<>();
                for (Tuple row : jkWork) {
                    if (row.get("useruuid").equals(jkUuid) && row.get("month").equals(month)) {
                        monthlyClientHrs.merge(month, ((Number) row.get("registered_hours")).doubleValue(), Double::sum);
                    }
                }
                if (salHrs > 0 && monthlyClientHrs.getOrDefault(month, 0.0) == 0) {
                    unassignedMonths++;
                }
            }
            boolean isUnassigned = unassignedMonths > 1;

            var summary = new JkTeamMemberSummary();
            summary.setJkUuid(jkUuid);
            summary.setJkName(userNames.getOrDefault(jkUuid, jkUuid));
            summary.setCurrentStatus(currentStatusMap.getOrDefault(jkUuid, "UNKNOWN"));
            summary.setMonthsAsStudent(monthsAsStudent);
            summary.setSalaryHours(totalSalaryHrs);
            summary.setClientHours(clientHrs);
            summary.setBillingPercent(billingPct);
            summary.setAvgRate(avgRate);
            summary.setTopClients(topClients);
            summary.setNetProfitLoss(pnlMap.getOrDefault(jkUuid, 0.0));
            summary.setUnassigned(isUnassigned);
            summary.setUnassignedMonths(unassignedMonths);

            result.add(summary);
        }

        result.sort(Comparator.comparing(JkTeamMemberSummary::getJkName));
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  CONVERSIONS
    // ═══════════════════════════════════════════════════════════════════════

    public List<JkConversionEntry> getConversions() {
        @SuppressWarnings("unchecked")
        List<Tuple> statusRows = em.createNativeQuery("""
                SELECT us.useruuid, us.type, us.statusdate
                FROM userstatus us
                WHERE us.useruuid IN (SELECT DISTINCT useruuid FROM userstatus WHERE type = 'STUDENT')
                ORDER BY us.useruuid, us.statusdate
                """, Tuple.class)
                .getResultList();

        // Group by user
        var userStatuses = new LinkedHashMap<String, List<Object[]>>();
        for (Tuple row : statusRows) {
            String uuid = (String) row.get("useruuid");
            String type = (String) row.get("type");
            LocalDate date = toLocalDate(row.get("statusdate"));
            userStatuses.computeIfAbsent(uuid, k -> new ArrayList<>())
                    .add(new Object[]{type, date});
        }

        var jkUuids = new ArrayList<>(userStatuses.keySet());
        var userNames = loadUserNames(jkUuids);

        var result = new ArrayList<JkConversionEntry>();
        for (var entry : userStatuses.entrySet()) {
            String jkUuid = entry.getKey();
            var statuses = entry.getValue();

            LocalDate firstStudentDate = null;
            LocalDate firstConsultantAfterStudent = null;
            boolean wasStudent = false;

            for (Object[] s : statuses) {
                String type = (String) s[0];
                LocalDate date = (LocalDate) s[1];

                if ("STUDENT".equals(type)) {
                    if (firstStudentDate == null) {
                        firstStudentDate = date;
                    }
                    wasStudent = true;
                } else if ("CONSULTANT".equals(type) && wasStudent) {
                    if (firstConsultantAfterStudent == null) {
                        firstConsultantAfterStudent = date;
                    }
                }
            }

            if (firstStudentDate == null) continue;

            boolean isConverted = firstConsultantAfterStudent != null;
            int durationMonths = isConverted
                    ? (int) ChronoUnit.MONTHS.between(firstStudentDate, firstConsultantAfterStudent)
                    : (int) ChronoUnit.MONTHS.between(firstStudentDate, LocalDate.now());

            result.add(new JkConversionEntry(
                    jkUuid,
                    userNames.getOrDefault(jkUuid, jkUuid),
                    firstStudentDate,
                    firstConsultantAfterStudent,
                    durationMonths,
                    isConverted
            ));
        }

        // Sort: converted first (by conversion date), then pipeline (by duration desc)
        result.sort(Comparator.comparing(JkConversionEntry::isConverted).reversed()
                .thenComparing(e -> e.getConsultantStartDate() != null ? e.getConsultantStartDate() : LocalDate.MAX));

        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MENTOR PAIRINGS
    // ═══════════════════════════════════════════════════════════════════════

    public List<MentorPairingEntry> getMentorPairings(int fiscalYear) {
        return getMentorPairings(fiscalYear, null);
    }

    List<MentorPairingEntry> getMentorPairings(int fiscalYear, Map<String, LocalDate> precomputedTenure) {
        var jkUuids = findAllEverStudentUuids();
        if (jkUuids.isEmpty()) return List.of();

        var tenureEndDates = precomputedTenure != null ? precomputedTenure : getStudentTenureEndDates(fiscalYear);

        // 1. Explicit pairings via workas — fetch raw then filter by tenure
        @SuppressWarnings("unchecked")
        List<Tuple> explicitRowsRaw = em.createNativeQuery("""
                SELECT wf.useruuid AS jk_uuid, wf.workas AS senior_uuid,
                       wf.clientuuid,
                       DATE_FORMAT(wf.registered, '%Y-%m') AS month,
                       SUM(wf.workduration) AS paired_hours
                FROM work_full wf
                WHERE wf.useruuid IN (:jkUuids)
                  AND wf.workas IS NOT NULL AND wf.workas != '' AND wf.workas != wf.useruuid
                  AND wf.taskuuid != :salaryTask
                  AND wf.clientuuid != :internalClient
                  AND wf.registered >= :fyStart AND wf.registered < :fyEnd
                GROUP BY wf.useruuid, wf.workas, wf.clientuuid, DATE_FORMAT(wf.registered, '%Y-%m')
                """, Tuple.class)
                .setParameter("jkUuids", jkUuids)
                .setParameter("salaryTask", SALARY_TASK_UUID)
                .setParameter("internalClient", INTERNAL_CLIENT_UUID)
                .setParameter("fyStart", fyStart(fiscalYear))
                .setParameter("fyEnd", fyEnd(fiscalYear))
                .getResultList();

        // Filter explicit pairings by student tenure
        var explicitRows = explicitRowsRaw.stream()
                .filter(row -> isMonthWithinTenure((String) row.get("jk_uuid"),
                        (String) row.get("month"), tenureEndDates))
                .toList();

        // 2. Shared-project pairings: CTE-based pre-aggregation to prevent N*M cross-join
        @SuppressWarnings("unchecked")
        List<Tuple> sharedRows = em.createNativeQuery("""
                WITH jk_agg AS (
                    SELECT wf.useruuid, wf.projectuuid, wf.clientuuid,
                           DATE_FORMAT(wf.registered, '%Y-%m') AS month,
                           SUM(wf.workduration) AS hours
                    FROM work_full wf
                    WHERE wf.useruuid IN (:jkUuids)
                      AND wf.taskuuid != :salaryTask
                      AND wf.clientuuid != :internalClient
                      AND (wf.workas IS NULL OR wf.workas = '' OR wf.workas = wf.useruuid)
                      AND wf.registered >= :fyStart AND wf.registered < :fyEnd
                    GROUP BY wf.useruuid, wf.projectuuid, wf.clientuuid, DATE_FORMAT(wf.registered, '%Y-%m')
                ),
                senior_agg AS (
                    SELECT wf.useruuid, wf.projectuuid,
                           DATE_FORMAT(wf.registered, '%Y-%m') AS month,
                           SUM(wf.workduration) AS hours
                    FROM work_full wf
                    WHERE wf.useruuid NOT IN (:jkUuids)
                      AND wf.taskuuid != :salaryTask
                      AND wf.clientuuid != :internalClient
                      AND wf.registered >= :fyStart AND wf.registered < :fyEnd
                    GROUP BY wf.useruuid, wf.projectuuid, DATE_FORMAT(wf.registered, '%Y-%m')
                )
                SELECT jk.useruuid AS jk_uuid, s.useruuid AS senior_uuid,
                       jk.clientuuid, jk.month,
                       LEAST(jk.hours, s.hours) AS paired_hours
                FROM jk_agg jk
                JOIN senior_agg s ON jk.projectuuid = s.projectuuid AND jk.month = s.month
                """, Tuple.class)
                .setParameter("jkUuids", jkUuids)
                .setParameter("salaryTask", SALARY_TASK_UUID)
                .setParameter("internalClient", INTERNAL_CLIENT_UUID)
                .setParameter("fyStart", fyStart(fiscalYear))
                .setParameter("fyEnd", fyEnd(fiscalYear))
                .getResultList();

        // Filter shared pairings by student tenure
        sharedRows = sharedRows.stream()
                .filter(row -> isMonthWithinTenure((String) row.get("jk_uuid"),
                        (String) row.get("month"), tenureEndDates))
                .collect(Collectors.toList());

        // Collect all UUIDs for name lookup
        Set<String> allUuids = new HashSet<>(jkUuids);
        Set<String> allClientUuids = new HashSet<>();
        for (Tuple row : explicitRows) {
            allUuids.add((String) row.get("senior_uuid"));
            allClientUuids.add((String) row.get("clientuuid"));
        }
        for (Tuple row : sharedRows) {
            allUuids.add((String) row.get("senior_uuid"));
            allClientUuids.add((String) row.get("clientuuid"));
        }
        var userNames = loadUserNames(new ArrayList<>(allUuids));
        var clientNameMap = loadClientNames(allClientUuids);

        // Build senior -> JK pairing map
        // Key: seniorUuid, Value: Map<jkUuid, PairingInfo>
        var seniorMap = new LinkedHashMap<String, Map<String, PairingInfo>>();

        for (Tuple row : explicitRows) {
            String jkUuid = (String) row.get("jk_uuid");
            String seniorUuid = (String) row.get("senior_uuid");
            String clientUuid = (String) row.get("clientuuid");
            double hours = ((Number) row.get("paired_hours")).doubleValue();

            var jkMap = seniorMap.computeIfAbsent(seniorUuid, k -> new LinkedHashMap<>());
            var info = jkMap.computeIfAbsent(jkUuid, k -> new PairingInfo());
            info.explicitHours += hours;
            info.clients.add(clientNameMap.getOrDefault(clientUuid, clientUuid));
        }

        for (Tuple row : sharedRows) {
            String jkUuid = (String) row.get("jk_uuid");
            String seniorUuid = (String) row.get("senior_uuid");
            String clientUuid = (String) row.get("clientuuid");
            double hours = ((Number) row.get("paired_hours")).doubleValue();

            var jkMap = seniorMap.computeIfAbsent(seniorUuid, k -> new LinkedHashMap<>());
            var info = jkMap.computeIfAbsent(jkUuid, k -> new PairingInfo());
            info.sharedHours += hours;
            info.clients.add(clientNameMap.getOrDefault(clientUuid, clientUuid));
        }

        // Build response
        var result = new ArrayList<MentorPairingEntry>();
        for (var entry : seniorMap.entrySet()) {
            String seniorUuid = entry.getKey();
            var jkMap = entry.getValue();

            double totalHours = 0;
            boolean hasExplicit = false;
            boolean hasShared = false;
            Set<String> allClients = new TreeSet<>();
            var jkDetails = new ArrayList<MentorPairingJkDetail>();

            for (var jkEntry : jkMap.entrySet()) {
                String jkUuid = jkEntry.getKey();
                var info = jkEntry.getValue();
                double hours = info.explicitHours + info.sharedHours;
                totalHours += hours;
                allClients.addAll(info.clients);

                String type;
                if (info.explicitHours > 0 && info.sharedHours > 0) {
                    type = "BOTH";
                    hasExplicit = true;
                    hasShared = true;
                } else if (info.explicitHours > 0) {
                    type = "EXPLICIT";
                    hasExplicit = true;
                } else {
                    type = "SHARED_PROJECT";
                    hasShared = true;
                }

                jkDetails.add(new MentorPairingJkDetail(jkUuid,
                        userNames.getOrDefault(jkUuid, jkUuid), hours, type));
            }

            String pairingType;
            if (hasExplicit && hasShared) pairingType = "BOTH";
            else if (hasExplicit) pairingType = "EXPLICIT";
            else pairingType = "SHARED_PROJECT";

            result.add(new MentorPairingEntry(
                    seniorUuid,
                    userNames.getOrDefault(seniorUuid, seniorUuid),
                    jkMap.size(),
                    totalHours,
                    new ArrayList<>(allClients),
                    pairingType,
                    jkDetails
            ));
        }

        result.sort(Comparator.comparingDouble(MentorPairingEntry::getTotalPairedHours).reversed());
        return result;
    }

    /** Internal helper for aggregating pairing info */
    private static class PairingInfo {
        double explicitHours;
        double sharedHours;
        Set<String> clients = new TreeSet<>();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  CLIENT CONCENTRATION
    // ═══════════════════════════════════════════════════════════════════════

    public List<ClientConcentrationEntry> getClientConcentration(int fiscalYear) {
        return getClientConcentration(fiscalYear, null);
    }

    List<ClientConcentrationEntry> getClientConcentration(int fiscalYear,
                                                           Map<String, LocalDate> precomputedTenure) {
        var jkUuids = findAllEverStudentUuids();
        if (jkUuids.isEmpty()) return List.of();

        var tenureEndDates = precomputedTenure != null ? precomputedTenure : getStudentTenureEndDates(fiscalYear);

        // Fetch and filter work by tenure, then aggregate per client in Java
        var jkWork = filterWorkByTenure(queryJkClientWork(jkUuids, fiscalYear), tenureEndDates);

        // Aggregate: clientUuid -> [jkUuids set, totalHours, potentialRevenue, sumRate*hours, rateHours]
        var clientAgg = new HashMap<String, double[]>();
        var clientJkSets = new HashMap<String, Set<String>>();

        for (Tuple row : jkWork) {
            String clientUuid = (String) row.get("clientuuid");
            String jkUuid = (String) row.get("useruuid");
            double hours = ((Number) row.get("registered_hours")).doubleValue();
            double rate = ((Number) row.get("avg_rate")).doubleValue();

            double[] agg = clientAgg.computeIfAbsent(clientUuid, k -> new double[4]);
            agg[0] += hours; // total_hours
            if (rate > 1) {
                agg[1] += hours * rate; // potential_revenue
                agg[2] += rate * hours; // weighted rate sum
                agg[3] += hours;        // rate-hours for weighted avg
            }
            clientJkSets.computeIfAbsent(clientUuid, k -> new HashSet<>()).add(jkUuid);
        }

        Set<String> allClientUuids = clientAgg.keySet();
        var clientNameMap = loadClientNames(allClientUuids);

        var result = new ArrayList<ClientConcentrationEntry>();
        // Sort by total hours descending
        var sortedClients = clientAgg.entrySet().stream()
                .sorted(Map.Entry.<String, double[]>comparingByValue(Comparator.comparingDouble(d -> -d[0])))
                .toList();

        for (var entry : sortedClients) {
            String clientUuid = entry.getKey();
            double[] agg = entry.getValue();
            int jkCount = clientJkSets.getOrDefault(clientUuid, Set.of()).size();
            double avgRate = agg[3] > 0 ? agg[2] / agg[3] : 0;

            result.add(new ClientConcentrationEntry(
                    clientUuid,
                    clientNameMap.getOrDefault(clientUuid, clientUuid),
                    jkCount,
                    agg[0],  // total_hours
                    agg[1],  // potential_revenue
                    avgRate
            ));
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PER-JK P&L
    // ═══════════════════════════════════════════════════════════════════════

    public List<JkPnlEntry> getPerJkPnl(int fiscalYear) {
        return getPerJkPnl(fiscalYear, null, null);
    }

    List<JkPnlEntry> getPerJkPnl(int fiscalYear,
                                   List<BillingTraceabilityRow> precomputedTraceability,
                                   Map<String, LocalDate> precomputedTenure) {
        var jkUuids = findAllEverStudentUuids();
        var tenureEndDates = precomputedTenure != null ? precomputedTenure : getStudentTenureEndDates(fiscalYear);
        var salaryHoursMap = filterSalaryHoursByTenure(queryJkSalaryHours(jkUuids, fiscalYear), tenureEndDates);
        var salaryRateMap = loadSalaryRateMap(jkUuids);
        var fyMonths = fyMonthKeys(fiscalYear);
        var jkRevenue = queryJkActualRevenue(jkUuids, fiscalYear);
        var traceability = precomputedTraceability != null ? precomputedTraceability : getBillingTraceability(fiscalYear);
        var userNames = loadUserNames(jkUuids);

        var result = new ArrayList<JkPnlEntry>();
        for (String jkUuid : jkUuids) {
            double totalSalaryCost = 0;
            int activeMonths = 0;
            for (String month : fyMonths) {
                String key = jkUuid + "|" + month;
                double hrs = salaryHoursMap.getOrDefault(key, 0.0);
                int rate = getHourlyRate(salaryRateMap, jkUuid, month);
                totalSalaryCost += hrs * rate;
                if (hrs > 0) activeMonths++;
            }
            double overheadCost = OVERHEAD_PER_JK_PER_MONTH * activeMonths;

            // Direct revenue — only during student tenure
            double directRevenue = 0;
            LocalDate tenureEnd = tenureEndDates.get(jkUuid);
            for (var entry : jkRevenue.entrySet()) {
                if (entry.getKey().startsWith(jkUuid + "|")) {
                    String monthKey = entry.getKey().substring(entry.getKey().lastIndexOf('|') + 1);
                    LocalDate monthStart = YearMonth.parse(monthKey).atDay(1);
                    if (tenureEnd != null && monthStart.isBefore(tenureEnd)) {
                        directRevenue += entry.getValue();
                    }
                }
            }

            // Merged revenue
            double mergedRevenue = 0;
            for (BillingTraceabilityRow row : traceability) {
                if (row.getJkUuid().equals(jkUuid) && row.getMergeDetails() != null
                        && (row.getScenario() == BillingScenario.POSSIBLY_MERGED || row.getScenario() == BillingScenario.HELP_MERGED)) {
                    mergedRevenue += Math.min(row.getMergeDetails().getSurplusHours() * row.getAvgRate(),
                            row.getRegisteredHours() * row.getAvgRate());
                }
            }

            // Skip JKs with no activity
            if (totalSalaryCost == 0 && directRevenue == 0 && mergedRevenue == 0) continue;

            double totalCost = totalSalaryCost + overheadCost;
            double totalRevenue = directRevenue + mergedRevenue;
            double netPL = totalRevenue - totalCost;

            result.add(new JkPnlEntry(jkUuid,
                    userNames.getOrDefault(jkUuid, jkUuid),
                    totalSalaryCost, overheadCost, totalCost,
                    directRevenue, mergedRevenue, totalRevenue, netPL));
        }

        // Sort by net P&L descending (most profitable first)
        result.sort(Comparator.comparingDouble(JkPnlEntry::getNetProfitLoss).reversed());
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  CACHED ACTIVE JK COUNT
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns countActiveJks using an optional cache to avoid redundant SQL calls.
     * If cache is null, falls back to the database query.
     */
    private int countActiveJksCached(LocalDate asOfDate, Map<LocalDate, Integer> cache) {
        if (cache == null) return countActiveJks(asOfDate);
        return cache.computeIfAbsent(asOfDate, this::countActiveJks);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  COMBINED: ALL DASHBOARD DATA IN ONE CALL
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns all JK dashboard data in a single response.
     * <p>
     * Computes {@code getBillingTraceability()} once and passes it to all
     * dependent methods, eliminating the redundancy that causes timeouts
     * when 11 individual endpoints are called simultaneously.
     */
    public JkDashboardAllResponse getAll(int fiscalYear) {
        log.infof("Computing all JK dashboard data for FY %d", fiscalYear);
        long start = System.currentTimeMillis();

        // Cache for countActiveJks calls (avoids ~10+ redundant SQL queries)
        var activeJkCache = new HashMap<LocalDate, Integer>();

        // Compute student tenure end dates ONCE — used by all tenure-aware methods
        var tenureEndDates = getStudentTenureEndDates(fiscalYear);

        // 1. Compute billing traceability ONCE (the most expensive computation)
        var billingTraceability = getBillingTraceability(fiscalYear, tenureEndDates);
        log.debugf("  billingTraceability: %d rows (%d ms)", billingTraceability.size(), System.currentTimeMillis() - start);

        // 2. Compute revenue leakage from pre-computed traceability
        var jkUuids = findAllEverStudentUuids();
        var revenueLeakage = getRevenueLeakage(fiscalYear, billingTraceability, jkUuids, tenureEndDates);
        log.debugf("  revenueLeakage: done (%d ms)", System.currentTimeMillis() - start);

        // 3. KPIs (uses pre-computed leakage + cached active counts + tenure)
        var kpis = computeKpis(fiscalYear, revenueLeakage, activeJkCache, tenureEndDates);
        // Add previous FY for YoY comparison
        try {
            var prevTenure = getStudentTenureEndDates(fiscalYear - 1);
            var prevKpi = computeKpis(fiscalYear - 1, null, activeJkCache, prevTenure);
            kpis.setPreviousFy(prevKpi);
        } catch (Exception e) {
            log.debugf("No previous FY data for %d: %s", fiscalYear - 1, e.getMessage());
        }
        log.debugf("  kpis: done (%d ms)", System.currentTimeMillis() - start);

        // 4. Profitability (uses pre-computed leakage + cached active counts + tenure)
        var profitability = getProfitability(fiscalYear, revenueLeakage, activeJkCache, tenureEndDates);
        log.debugf("  profitability: done (%d ms)", System.currentTimeMillis() - start);

        // 5. Rate analysis (uses pre-computed traceability + tenure)
        var rateAnalysis = getRateAnalysis(fiscalYear, billingTraceability, tenureEndDates);
        log.debugf("  rateAnalysis: done (%d ms)", System.currentTimeMillis() - start);

        // 6. Salary analysis (uses tenure)
        var salaryAnalysis = getSalaryAnalysis(fiscalYear, tenureEndDates);
        log.debugf("  salaryAnalysis: done (%d ms)", System.currentTimeMillis() - start);

        // 7. Team overview (uses pre-computed traceability + leakage + tenure)
        var teamOverview = getTeamOverview(fiscalYear, billingTraceability, revenueLeakage, tenureEndDates);
        log.debugf("  teamOverview: done (%d ms)", System.currentTimeMillis() - start);

        // 8. Conversions (no traceability dependency — independent query, unchanged)
        var conversions = getConversions();
        log.debugf("  conversions: done (%d ms)", System.currentTimeMillis() - start);

        // 9. Mentor pairings (uses tenure)
        var mentorPairings = getMentorPairings(fiscalYear, tenureEndDates);
        log.debugf("  mentorPairings: done (%d ms)", System.currentTimeMillis() - start);

        // 10. Client concentration (uses tenure)
        var clientConcentration = getClientConcentration(fiscalYear, tenureEndDates);
        log.debugf("  clientConcentration: done (%d ms)", System.currentTimeMillis() - start);

        // 11. Per-JK P&L (uses pre-computed traceability + tenure)
        var perJkPnl = getPerJkPnl(fiscalYear, billingTraceability, tenureEndDates);
        log.debugf("  perJkPnl: done (%d ms)", System.currentTimeMillis() - start);

        long total = System.currentTimeMillis() - start;
        log.infof("All JK dashboard data computed in %d ms", total);

        return new JkDashboardAllResponse(
                kpis, profitability, revenueLeakage, billingTraceability,
                rateAnalysis, salaryAnalysis, teamOverview, conversions,
                mentorPairings, clientConcentration, perJkPnl
        );
    }
}
