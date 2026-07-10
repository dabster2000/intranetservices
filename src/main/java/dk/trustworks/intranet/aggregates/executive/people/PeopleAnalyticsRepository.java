package dk.trustworks.intranet.aggregates.executive.people;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Map;

import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.CXO_QUERY_TIMEOUT_MS;

/** Small, read-only native-query boundary for Executive people analytics. */
@JBossLog
@ApplicationScoped
public class PeopleAnalyticsRepository {

    @Inject
    EntityManager entityManager;

    public List<Tuple> tuples(String operation, String sql, Map<String, ?> parameters) {
        Query query = entityManager.createNativeQuery(sql, Tuple.class);
        parameters.forEach(query::setParameter);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
        try {
            @SuppressWarnings("unchecked")
            List<Tuple> rows = query.getResultList();
            return rows;
        } catch (PersistenceException exception) {
            // Do not log filter values: career/pay slices can be identifying.
            log.errorf(exception, "Executive people analytics query failed: %s", operation);
            throw exception;
        }
    }
}
