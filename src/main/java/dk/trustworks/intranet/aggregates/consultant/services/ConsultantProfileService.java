package dk.trustworks.intranet.aggregates.consultant.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import dk.trustworks.intranet.aggregates.consultant.dto.ConsultantProfileDTO;
import dk.trustworks.intranet.aggregates.consultant.model.ConsultantProfile;
import dk.trustworks.intranet.aggregates.consultant.services.CvHighlightsExtractor.CvHighlights;
import dk.trustworks.intranet.cvtool.entity.CvToolEmployeeCv;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read path for the dashboard "Available Now" card.
 *
 * <p><b>Rule, non-negotiable:</b> {@code GET /api/consultants/profiles} must not make an OpenAI
 * call on the request thread, must not hold a pooled database connection across any network call,
 * and must return in well under a second for the {@code MAX_UUIDS} cap. It serves whatever is
 * cached plus the deterministic CV facts, and <em>enqueues</em> regeneration for anything stale.
 *
 * <p>Three strictly ordered phases:
 * <ol>
 *   <li><b>A — snapshot</b>: one short transaction, database only, no network.</li>
 *   <li><b>B — build</b>: pure; exactly one DTO per requested uuid, in request order.</li>
 *   <li><b>C — enqueue</b>: hands stale consultants to the background generator; wrapped in
 *       try/catch, because a read must never fail because of an enqueue.</li>
 * </ol>
 *
 * <p>The method itself is deliberately <b>not</b> {@code @Transactional}: the entities it reads
 * are detached after phase A and are only ever read through getters. Nothing here writes.
 */
@JBossLog
@ApplicationScoped
public class ConsultantProfileService {

    /**
     * Hard cap on distinct client uuids resolved for the industry derivation. 10 consultants ×
     * ~40 projects is the realistic worst case, so this only ever bites on malformed CV data.
     */
    static final int MAX_CLIENT_LOOKUPS = 500;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    CvHighlightsExtractor cvHighlightsExtractor;

    @Inject
    ConsultantProfileGenerationService generationService;

    /** Everything phases B and C need, read once, in one transaction. */
    record ReadSnapshot(
            Map<String, ConsultantProfile> profiles,
            Map<String, CvToolEmployeeCv> cvs,
            Map<String, JsonNode> cvNodes,
            Map<String, ClientSegment> segments
    ) {
    }

    /**
     * Returns one profile per requested uuid, in request order, and schedules regeneration for
     * anything stale. Never blocks on OpenAI.
     */
    public List<ConsultantProfileDTO> getProfiles(List<String> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return List.of();
        }
        if (QuarkusTransaction.isActive()) {
            // A caller-supplied transaction would be held while we enqueue and would propagate
            // onto the ManagedExecutor worker together with its pooled connection.
            throw new IllegalStateException("getProfiles must not be called inside a transaction");
        }

        ReadSnapshot snapshot = QuarkusTransaction.requiringNew().call(() -> loadSnapshot(uuids));
        List<ConsultantProfileDTO> profiles = buildDtos(uuids, snapshot);
        enqueueStale(uuids, snapshot);
        return profiles;
    }

    // ---- Phase A ---------------------------------------------------------------

    ReadSnapshot loadSnapshot(List<String> uuids) {
        Map<String, ConsultantProfile> profiles = new HashMap<>();
        for (ConsultantProfile profile : ConsultantProfile.<ConsultantProfile>list("useruuid in ?1", uuids)) {
            profiles.put(profile.getUseruuid(), profile);
        }

        Map<String, List<CvToolEmployeeCv>> cvsByUser = new HashMap<>();
        for (CvToolEmployeeCv cv : CvToolEmployeeCv.<CvToolEmployeeCv>list("useruuid in ?1", uuids)) {
            cvsByUser.computeIfAbsent(cv.getUseruuid(), key -> new ArrayList<>()).add(cv);
        }

        Map<String, CvToolEmployeeCv> cvs = new HashMap<>();
        Map<String, JsonNode> cvNodes = new HashMap<>();
        Set<String> clientUuids = new LinkedHashSet<>();
        cvsByUser.forEach((useruuid, candidates) -> {
            CvToolEmployeeCv cv = ConsultantProfileGenerationService.pickCv(candidates);
            if (cv == null) {
                return;
            }
            cvs.put(useruuid, cv);
            JsonNode root = parseCv(useruuid, cv.getCvDataJson());
            cvNodes.put(useruuid, root);
            collectClientUuids(root, clientUuids);
        });

        return new ReadSnapshot(Map.copyOf(profiles), Map.copyOf(cvs), Map.copyOf(cvNodes),
                loadSegments(clientUuids));
    }

    /** A malformed CV blob must degrade to "no deterministic facts", never to an exception. */
    private JsonNode parseCv(String useruuid, String cvDataJson) {
        if (cvDataJson == null || cvDataJson.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            JsonNode root = objectMapper.readTree(cvDataJson);
            return root == null ? MissingNode.getInstance() : root;
        } catch (Exception e) {
            // Never log the CV body — it is employee content.
            log.debugf("Unparseable cv_data_json for %s (length %d) — serving without CV facts",
                    useruuid, cvDataJson.length());
            return MissingNode.getInstance();
        }
    }

    private static void collectClientUuids(JsonNode cvRoot, Set<String> target) {
        JsonNode projects = cvRoot.path("projects");
        if (!projects.isArray()) {
            return;
        }
        for (JsonNode project : projects) {
            if (target.size() >= MAX_CLIENT_LOOKUPS) {
                return;
            }
            JsonNode clientUuid = project.path("client_uuid");
            if (clientUuid.isTextual() && !clientUuid.asText().isBlank()) {
                target.add(clientUuid.asText().trim());
            }
        }
    }

    private static Map<String, ClientSegment> loadSegments(Set<String> clientUuids) {
        if (clientUuids.isEmpty()) {
            return Map.of();
        }
        Map<String, ClientSegment> segments = new HashMap<>();
        for (Client client : Client.<Client>list("uuid in ?1", List.copyOf(clientUuids))) {
            if (client.getSegment() != null) {
                segments.put(client.getUuid(), client.getSegment());
            }
        }
        return Map.copyOf(segments);
    }

    // ---- Phase B ---------------------------------------------------------------

    List<ConsultantProfileDTO> buildDtos(List<String> uuids, ReadSnapshot snapshot) {
        List<ConsultantProfileDTO> out = new ArrayList<>(uuids.size());
        for (String useruuid : uuids) {
            try {
                out.add(buildDto(useruuid, snapshot));
            } catch (Exception e) {
                // One bad row must never cost the whole batch its response.
                log.errorf(e, "Could not build consultant profile DTO for %s", useruuid);
                out.add(ConsultantProfileDTO.empty(useruuid));
            }
        }
        return out;
    }

    private ConsultantProfileDTO buildDto(String useruuid, ReadSnapshot snapshot) {
        CvToolEmployeeCv cv = snapshot.cvs().get(useruuid);
        ConsultantProfile profile = snapshot.profiles().get(useruuid);
        if (cv == null) {
            // No CV row: nothing deterministic to derive. A previously generated pitch (should one
            // exist) is still served rather than thrown away.
            return profile == null
                    ? ConsultantProfileDTO.empty(useruuid)
                    : ConsultantProfileDTO.from(useruuid, profile, CvHighlights.empty());
        }
        CvHighlights highlights = cvHighlightsExtractor.extract(
                cv.getEmployeeTitle(),
                snapshot.cvNodes().get(useruuid),
                snapshot.segments());
        return ConsultantProfileDTO.from(useruuid, profile, highlights);
    }

    // ---- Phase C ---------------------------------------------------------------

    void enqueueStale(List<String> uuids, ReadSnapshot snapshot) {
        try {
            int backoffMinutes = generationService.retryBackoffMinutes();
            for (String useruuid : uuids) {
                CvToolEmployeeCv cv = snapshot.cvs().get(useruuid);
                if (cv == null) {
                    continue; // nothing to generate from
                }
                ConsultantProfile profile = snapshot.profiles().get(useruuid);
                if (profile != null
                        && (!profile.isStale(cv.getCvLastUpdatedAt()) || !profile.shouldAttempt(backoffMinutes))) {
                    continue;
                }
                generationService.enqueue(useruuid);
            }
        } catch (Exception e) {
            log.errorf(e, "Could not enqueue consultant profile generation for %d uuid(s)", uuids.size());
        }
    }
}
