package dk.trustworks.intranet.messaging.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.messaging.dto.EventData;
import dk.trustworks.intranet.messaging.outbox.OutboxEvent;
import dk.trustworks.intranet.messaging.producer.KafkaEventPublisher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@JBossLog
@Path("/internal/backfill")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BackfillResource {

    @Inject
    KafkaEventPublisher publisher;

    @Inject
    dk.trustworks.intranet.messaging.config.ConfigTopicMapper topicMapper;

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final List<String> DATE_FIELDS = List.of(
            "aggregateDate", "statusdate", "activefrom", "registered", "date", "documentDate"
    );

    @POST
    @Path("/outbox")
    @Transactional
    public Response backfillOutbox(
            @QueryParam("start") String start,
            @QueryParam("end") String end,
            @QueryParam("types") String types,
            @QueryParam("limit") @DefaultValue("1000") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("dryRun") @DefaultValue("true") boolean dryRun
    ) throws Exception {
        LocalDateTime from = (start == null || start.isBlank()) ? LocalDate.now().minusYears(1).atStartOfDay() : LocalDateTime.parse(start);
        LocalDateTime to = (end == null || end.isBlank()) ? LocalDateTime.now() : LocalDateTime.parse(end);
        List<String> typeList = parseTypes(types);

        String baseQuery = "occurredAt between ?1 and ?2";
        List<Object> params = new ArrayList<>(List.of(from, to));
        if (!typeList.isEmpty()) {
            baseQuery += " and type in ?3";
            params.add(typeList);
        }

        var q = OutboxEvent.<OutboxEvent>find(baseQuery, params.toArray());
        long total = q.count();
        List<OutboxEvent> page = q.page(offset / limit, limit).list();

        BackfillSummary summary = new BackfillSummary();
        summary.setTotalSelected(total);
        summary.setProduced(0);
        summary.setDryRun(dryRun);

        for (OutboxEvent evt : page) {
            String topic = topicMapper.topicForType(evt.getType());
            if (topic == null) continue; // skip non-external types
            try {
                String dateStr = deriveDate(evt);
                EventData ed = new EventData(evt.getAggregateId(), dateStr);
                String json = mapper.writeValueAsString(ed);
                if (!dryRun) {
                    publisher.send(topic, evt.getAggregateId(), json);
                }
                summary.incrementTopic(topic);
            } catch (Exception e) {
                log.errorf(e, "Failed to transform outbox event %s of type %s", evt.getId(), evt.getType());
                summary.incrementTopic("errors");
            }
        }
        if (!dryRun) publisher.flush();

        log.infof("Backfill summary dryRun=%s totalSelected=%d produced=%d perTopic=%s range=%s..%s types=%s limit=%d offset=%d",
                dryRun, total, summary.getProduced(), summary.getPerTopic(), from, to, typeList, limit, offset);
        return Response.ok(summary).build();
    }

    private static List<String> parseTypes(String types) {
        if (types == null || types.isBlank()) return Collections.emptyList();
        return Arrays.stream(types.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private String deriveDate(OutboxEvent evt) {
        try {
            if (evt.getPayload() != null && !evt.getPayload().isBlank()) {
                JsonNode root = mapper.readTree(evt.getPayload());
                for (String f : DATE_FIELDS) {
                    if (root.has(f) && !root.get(f).isNull()) {
                        String v = root.get(f).asText();
                        // Validate parsable; support ISO or dd.MM.yyyy via DateUtils at consumer side
                        if (!v.isBlank()) return v;
                    }
                }
            }
        } catch (Exception e) {
            log.debugf("Could not parse payload date for outbox %s: %s", evt.getId(), e.getMessage());
        }
        // Fallback: occurredAt in ISO date
        return evt.getOccurredAt().toLocalDate().format(DateTimeFormatter.ISO_DATE);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BackfillSummary {
        private long totalSelected;
        private long produced;
        private boolean dryRun;
        private Map<String, Long> perTopic = new HashMap<>();

        public void incrementTopic(String topic) {
            perTopic.merge(topic, 1L, Long::sum);
            if (!"errors".equals(topic)) produced++;
        }
    }
}
