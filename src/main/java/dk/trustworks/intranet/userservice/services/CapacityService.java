package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.userservice.dto.Capacity;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.Tuple;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class CapacityService {

    @Inject EntityManager em;

    public List<Capacity> calculateCapacityByPeriod(LocalDate fromDate, LocalDate toDate) {
        LocalDate currentDate = fromDate.withDayOfMonth(1);
        List<Capacity> capacityList = new ArrayList<>();
        do {
            capacityList.addAll(calculateCapacityByMonth(currentDate));
            currentDate = currentDate.plusMonths(1);
        } while (currentDate.isBefore(toDate));
        return capacityList;
    }

    public Capacity calculateCapacityByMonthByUser(String useruuid, LocalDate statusdate) {
        String sql = "SELECT COALESCE(sum(allocation), 0) as totalAllocation FROM user u RIGHT JOIN ( " +
                "select t.useruuid, t.status, t.statusdate, t.allocation " +
                "from userstatus t " +
                "inner join ( " +
                "select us.useruuid, us.status, max(us.statusdate) as MaxDate " +
                "from userstatus us WHERE us.statusdate <= :statusdate " +
                "group by us.useruuid " +
                ") " +
                "tm on t.useruuid = tm.useruuid and t.statusdate = tm.MaxDate " +
                ") usi ON u.uuid = usi.useruuid WHERE u.uuid LIKE :useruuid ;";

        Query query = em.createNativeQuery(sql);
        query.setParameter("useruuid", useruuid);
        query.setParameter("statusdate", statusdate);
        return new Capacity(useruuid, statusdate, ((Number) query.getSingleResult()).intValue());
    }

    public List<Capacity> calculateCapacityByMonth(LocalDate statusdate) {
        String sql = "SELECT u.uuid as useruuid, COALESCE(sum(allocation), 0) as totalAllocation FROM user u LEFT JOIN ( " +
                "select t.useruuid, t.status, t.statusdate, t.allocation " +
                "from userstatus t " +
                "inner join ( " +
                "select us.useruuid, us.status, max(us.statusdate) as MaxDate " +
                "from userstatus us WHERE us.statusdate <= :statusdate " +
                "group by us.useruuid " +
                ") " +
                "tm on t.useruuid = tm.useruuid and t.statusdate = tm.MaxDate " +
                ") usi ON u.uuid = usi.useruuid group by u.uuid;";

        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setParameter("statusdate", statusdate);

        List<Capacity> capacityList = ((Stream<Tuple>) query.getResultStream())
                .map(tuple -> new Capacity(tuple.get("useruuid", String.class), statusdate, tuple.get("totalAllocation", Number.class).intValue()))
                .collect(Collectors.toList());
        return capacityList;
    }
}