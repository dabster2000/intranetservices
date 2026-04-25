package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.entities.CandidateActivity;
import dk.trustworks.intranet.recruitmentservice.domain.entities.CandidateNote;
import dk.trustworks.intranet.recruitmentservice.domain.enums.CandidateState;
import dk.trustworks.intranet.recruitmentservice.domain.enums.Practice;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@ApplicationScoped
public class CandidateService {

    @Transactional
    public Candidate create(Candidate input, String actorUuid) {
        if (input.uuid == null) input.uuid = UUID.randomUUID().toString();
        if (input.state == null) input.state = CandidateState.NEW;
        if (input.consentStatus == null) input.consentStatus = "PENDING";
        input.createdAt = LocalDateTime.now();
        input.updatedAt = input.createdAt;
        input.persist();
        CandidateActivity.log(input.uuid, "CANDIDATE_CREATED", null, actorUuid).persist();
        return input;
    }

    public Candidate find(String uuid) {
        Candidate c = Candidate.findById(uuid);
        if (c == null) throw new NotFoundException("Candidate " + uuid);
        if (c.state == CandidateState.ANONYMIZED) {
            throw new NotFoundException("Candidate " + uuid);  // Hide anonymized
        }
        return c;
    }

    @Transactional
    public Candidate patch(String uuid, Consumer<Candidate> mutator) {
        Candidate c = find(uuid);
        mutator.accept(c);
        c.updatedAt = LocalDateTime.now();
        return c;
    }

    @Transactional
    public CandidateNote addNote(String candidateUuid, String body, CandidateNote.Visibility v, String authorUuid) {
        find(candidateUuid);
        CandidateNote n = CandidateNote.fresh(candidateUuid, authorUuid, body, v);
        n.persist();
        return n;
    }

    public List<Candidate> list(CandidateState state, String practice, String q, int page, int size) {
        StringBuilder where = new StringBuilder("state <> :anon");
        Map<String, Object> params = new HashMap<>();
        params.put("anon", CandidateState.ANONYMIZED);
        if (state != null) { where.append(" and state = :state"); params.put("state", state); }
        if (practice != null) {
            where.append(" and desiredPractice = :prac");
            params.put("prac", parseEnum(Practice.class, practice, "practice"));
        }
        if (q != null && !q.isBlank()) {
            where.append(" and (lower(firstName) like :q or lower(lastName) like :q or lower(email) like :q)");
            params.put("q", "%" + q.toLowerCase() + "%");
        }
        return Candidate.find(where.toString(), Sort.by("createdAt").descending(), params)
                .page(Page.of(page, size)).list();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String paramName) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid " + paramName + ": " + value);
        }
    }
}
