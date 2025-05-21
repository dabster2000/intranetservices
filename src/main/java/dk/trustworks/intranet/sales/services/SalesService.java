package dk.trustworks.intranet.sales.services;

import dk.trustworks.intranet.contracts.model.enums.SalesStatus;
import dk.trustworks.intranet.sales.model.SalesLead;
import dk.trustworks.intranet.sales.model.SalesLeadConsultant;
import dk.trustworks.intranet.sales.model.enums.LeadStatus;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@JBossLog
@ApplicationScoped
public class SalesService {

    public SalesLead findOne(String uuid) {
        return SalesLead.findById(uuid);
    }

    public List<SalesLead> findAll(int offset, int limit, List<String> sortOrders, String filter, String status) {
        Map<String, Object> params = new HashMap<>();
        List<String> conditions = new ArrayList<>();

        // Exclude WON and LOST statuses
        conditions.add("status NOT IN (:excludedStatuses)");
        params.put("excludedStatuses", Arrays.asList(LeadStatus.WON, LeadStatus.LOST));

        if (status != null && !status.isEmpty()) {
            conditions.add("status IN (:statusList)");
            params.put("statusList", Arrays.stream(status.split(",")).map(LeadStatus::valueOf).toList());
        }

        if (filter != null && !filter.isEmpty()) {
            conditions.add("(lower(description) LIKE :filter OR lower(client.name) LIKE :filter)");
            params.put("filter", "%" + filter.toLowerCase() + "%");
        }

        String queryString = String.join(" AND ", conditions);

        // Build the Sort object
        Sort sort = null;
        if (sortOrders != null && !sortOrders.isEmpty()) {
            for (String sortOrder : sortOrders) {
                String[] parts = sortOrder.split(":");
                String field = parts[0];
                Sort.Direction direction = (parts.length > 1 && "desc".equalsIgnoreCase(parts[1]))
                        ? Sort.Direction.Descending
                        : Sort.Direction.Ascending;

                if ("created".equals(field)) {
                    // Invert the sort direction to match age sorting
                    direction = direction == Sort.Direction.Ascending ? Sort.Direction.Descending : Sort.Direction.Ascending;
                }

                if (sort == null) {
                    sort = Sort.by(field, direction);
                } else {
                    sort = sort.and(field, direction);
                }
            }
        }

        PanacheQuery<SalesLead> query;
        if (sort != null) {
            query = SalesLead.find(queryString, sort, params);
        } else {
            query = SalesLead.find(queryString, params);
        }

        // Apply paging
        int pageNumber = offset / limit;
        query = query.page(Page.of(pageNumber, limit));

        List<SalesLead> list = query.list();
        System.out.println("list = " + list.size());
        return list;
    }

    public long count(String filter, String status) {
        System.out.println("SalesService.count");
        System.out.println("filter = " + filter + ", status = " + status);
        Map<String, Object> params = new HashMap<>();
        List<String> conditions = new ArrayList<>();

        // Exclude WON and LOST statuses
        conditions.add("status NOT IN (:excludedStatuses)");
        params.put("excludedStatuses", Arrays.asList(LeadStatus.WON, LeadStatus.LOST));

        if (status != null && !status.isEmpty()) {
            conditions.add("status IN (:statusList)");
            params.put("statusList", Arrays.stream(status.split(",")).map(LeadStatus::valueOf).toList());
        }

        if (filter != null && !filter.isEmpty()) {
            conditions.add("(lower(description) LIKE :filter OR lower(client.name) LIKE :filter)");
            params.put("filter", "%" + filter.toLowerCase() + "%");
        }

        String queryString = String.join(" AND ", conditions);

        long count = SalesLead.count(queryString, params);
        System.out.println("count = " + count);
        return count;
    }

    public List<SalesLead> findAll() {
        return SalesLead.findAll().<SalesLead>stream().sorted(Comparator.comparing(SalesLead::getCreated)).toList();
    }

    public List<SalesLead> findWon(LocalDate sinceDate) {
        LocalDateTime since = sinceDate.atStartOfDay();
        log.infof("since = %s", since);
        return SalesLead.list("status = ?1 and modified >= ?2", LeadStatus.WON, since);
    }

    public List<SalesLead> findByStatus(SalesStatus... status) {
        return SalesLead.find("IN ?1", Arrays.stream(status).toList()).list();
    }

    @Transactional
    public void persist(SalesLead salesLead) {
        if(salesLead.getUuid()==null || salesLead.getUuid().isBlank()) {
            salesLead.setUuid(UUID.randomUUID().toString());
            salesLead.setCreated(LocalDateTime.now());
            salesLead.persist();
        } else if(SalesLead.findById(salesLead.getUuid())==null) salesLead.persist();
        else update(salesLead);
    }

    @Transactional
    public void addConsultant(String salesLeaduuid, User user) {
        new SalesLeadConsultant(SalesLead.findById(salesLeaduuid), user).persist();
    }

    @Transactional
    public void removeConsultant(String salesleaduuid, String useruuid) {
        SalesLead salesLead = SalesLead.findById(salesleaduuid);
        User user = User.findById(useruuid);
        SalesLeadConsultant.delete("lead = ?1 and user = ?2", salesLead, user);
    }

    @Transactional
    public void update(SalesLead salesLead) {
        log.info("SalesService.update");
        log.info("salesLead = " + salesLead);
        SalesLead.update("client = ?1, " +
                        "allocation = ?2, " +
                        "closeDate = ?3, " +
                        "competencies = ?4, " +
                        "leadManager = ?5, " +
                        "extension = ?6, " +
                        "rate = ?7, " +
                        "period = ?8, " +
                        "contactInformation = ?9, " +
                        "description = ?10, " +
                        "detailedDescription = ?11, " +
                        "status = ?12 " +
                        "WHERE uuid like ?13 ",
                salesLead.getClient(),
                salesLead.getAllocation(),
                salesLead.getCloseDate(),
                salesLead.getCompetencies(),
                salesLead.getLeadManager(),
                //salesLead.getConsultantLevel(),
                salesLead.isExtension(),
                salesLead.getRate(),
                salesLead.getPeriod(),
                salesLead.getContactInformation(),
                salesLead.getDescription(),
                salesLead.getDetailedDescription(),
                salesLead.getStatus(),
                salesLead.getUuid());
    }

    @Transactional
    public void delete(String uuid) {
        SalesLead.deleteById(uuid);
    }

}
