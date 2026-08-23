package dk.trustworks.intranet.recruitmentservice.security;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast-tier parity and cost contract for the dossier-capability batch used by
 * the board and landing feed. All database seams are replaced with declared
 * facts so cardinality can grow without making the test itself issue queries.
 */
class RecruitmentVisibilityDossierBatchTest {

    private static final String VIEWER = "viewer";

    static final class BatchStubVisibility extends RecruitmentVisibility {
        final Map<String, Set<String>> roles = new HashMap<>();
        final Set<String> intakeHolders = new HashSet<>();
        final Set<String> gdprHolders = new HashSet<>();
        final List<HiringOwnerApplicationCreator> ownerRows = new ArrayList<>();

        int rolesLookups;
        int scalarOwnerLookups;
        int batchOwnerLookups;
        int standingBatchLookups;
        int gdprLookups;

        @Override
        public Set<String> rolesOf(String userUuid) {
            rolesLookups++;
            return roles.getOrDefault(userUuid, Set.of());
        }

        @Override
        boolean holdsRecruitmentIntakeGrant(String viewerUuid) {
            return intakeHolders.contains(viewerUuid);
        }

        @Override
        boolean holdsRecruitmentGdprGrant(String viewerUuid) {
            gdprLookups++;
            return gdprHolders.contains(viewerUuid);
        }

        @Override
        protected List<String> hiringOwnerApplicationCreators(
                String viewerUuid, String candidateUuid) {
            scalarOwnerLookups++;
            return ownerRows.stream()
                    .filter(row -> candidateUuid.equals(row.candidateUuid()))
                    .map(HiringOwnerApplicationCreator::creatorUuid)
                    .toList();
        }

        @Override
        protected List<HiringOwnerApplicationCreator> hiringOwnerApplicationCreators(
                String viewerUuid, Collection<String> candidateUuids) {
            batchOwnerLookups++;
            return ownerRows.stream()
                    .filter(row -> candidateUuids.contains(row.candidateUuid()))
                    .toList();
        }

        @Override
        protected Map<String, CreatorAuthorizationStanding> creatorAuthorizationStandings(
                Collection<String> creatorUuids) {
            standingBatchLookups++;
            return creatorUuids.stream().collect(Collectors.toMap(
                    creator -> creator,
                    creator -> new CreatorAuthorizationStanding(
                            roles.getOrDefault(creator, Set.of()),
                            intakeHolders.contains(creator))));
        }

        @Override
        public boolean isPartnerTrackOnly(String viewerUuid, String candidateUuid) {
            // Every owner row in this declared fact set represents either a
            // non-partner application or a partner application in the circle,
            // exactly as the production owner query guarantees.
            return false;
        }
    }

    @Test
    void batchMatchesScalarForCreatorRulesAndHiredCutoff() {
        List<RecruitmentCandidate> candidates = List.of(
                candidate("system", CandidateStatus.ACTIVE),
                candidate("blank", CandidateStatus.ACTIVE),
                candidate("self", CandidateStatus.ACTIVE),
                candidate("cross-intake", CandidateStatus.ACTIVE),
                candidate("recruiter-filed", CandidateStatus.ACTIVE),
                candidate("historical", CandidateStatus.ACTIVE),
                candidate("hired", CandidateStatus.HIRED),
                candidate("not-owned", CandidateStatus.ACTIVE));

        BatchStubVisibility scalar = declaredFacts();
        Set<String> scalarReadable = candidates.stream()
                .filter(candidate -> scalar.canReadDossier(VIEWER, candidate))
                .map(RecruitmentCandidate::getUuid)
                .collect(Collectors.toSet());

        BatchStubVisibility batched = declaredFacts();
        assertEquals(scalarReadable,
                batched.dossierReadableCandidateUuids(VIEWER, candidates));
        assertEquals(Set.of("system", "blank", "recruiter-filed", "historical"),
                scalarReadable);
    }

    @Test
    void batchPreservesAssistantAndAdditiveRoleSemantics() {
        List<RecruitmentCandidate> candidates = List.of(
                candidate("system", CandidateStatus.ACTIVE),
                candidate("not-owned", CandidateStatus.HIRED));

        BatchStubVisibility assistant = declaredFacts();
        assistant.roles.put(VIEWER, Set.of("ASSISTANT_TEAMLEAD"));
        assertTrue(assistant.dossierReadableCandidateUuids(VIEWER, candidates).isEmpty());
        assertEquals(0, assistant.batchOwnerLookups);
        assertEquals(0, assistant.standingBatchLookups);

        BatchStubVisibility mixed = declaredFacts();
        mixed.roles.put(VIEWER, Set.of("ASSISTANT_TEAMLEAD", "TEAMLEAD"));
        assertEquals(Set.of("system"),
                mixed.dossierReadableCandidateUuids(VIEWER, candidates));

        BatchStubVisibility recruitmentOnly = declaredFacts();
        recruitmentOnly.roles.put(VIEWER, Set.of("RECRUITMENT"));
        assertTrue(recruitmentOnly.dossierReadableCandidateUuids(VIEWER, candidates).isEmpty());
        assertEquals(0, recruitmentOnly.batchOwnerLookups);

        BatchStubVisibility hr = declaredFacts();
        hr.roles.put(VIEWER, Set.of("HR"));
        assertEquals(Set.of("system", "not-owned"),
                hr.dossierReadableCandidateUuids(VIEWER, candidates));
        assertEquals(0, hr.batchOwnerLookups);
        assertEquals(0, hr.standingBatchLookups);
    }

    @Test
    void batchLookupCountIsIndependentOfCandidateAndCreatorCardinality() {
        BatchStubVisibility visibility = new BatchStubVisibility();
        visibility.roles.put(VIEWER, Set.of("TEAMLEAD"));
        List<RecruitmentCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            String candidateUuid = "candidate-" + index;
            String creatorUuid = "creator-" + index;
            candidates.add(candidate(candidateUuid, CandidateStatus.ACTIVE));
            visibility.ownerRows.add(new RecruitmentVisibility.HiringOwnerApplicationCreator(
                    candidateUuid, creatorUuid));
        }

        assertEquals(200,
                visibility.dossierReadableCandidateUuids(VIEWER, candidates).size());
        assertEquals(1, visibility.rolesLookups,
                "viewer roles are resolved once for the whole batch");
        assertEquals(1, visibility.batchOwnerLookups,
                "all named-owner rows use one bounded lookup");
        assertEquals(1, visibility.standingBatchLookups,
                "all distinct creators use one bounded lookup");
        assertEquals(0, visibility.scalarOwnerLookups,
                "the batch must never fall back to a per-candidate predicate");
        assertEquals(0, visibility.gdprLookups,
                "active candidates do not need the hired-file grant");
    }

    private static BatchStubVisibility declaredFacts() {
        BatchStubVisibility visibility = new BatchStubVisibility();
        visibility.roles.put(VIEWER, Set.of("TEAMLEAD"));
        visibility.roles.put("intake-colleague", Set.of("TEAMLEAD"));
        visibility.roles.put("hr-creator", Set.of("HR"));
        visibility.intakeHolders.add("intake-colleague");
        visibility.ownerRows.addAll(List.of(
                new RecruitmentVisibility.HiringOwnerApplicationCreator("system", "system"),
                new RecruitmentVisibility.HiringOwnerApplicationCreator("blank", ""),
                new RecruitmentVisibility.HiringOwnerApplicationCreator("self", VIEWER),
                new RecruitmentVisibility.HiringOwnerApplicationCreator(
                        "cross-intake", "intake-colleague"),
                new RecruitmentVisibility.HiringOwnerApplicationCreator(
                        "recruiter-filed", "hr-creator"),
                new RecruitmentVisibility.HiringOwnerApplicationCreator(
                        "historical", "departed-user"),
                new RecruitmentVisibility.HiringOwnerApplicationCreator(
                        "hired", "hr-creator")));
        return visibility;
    }

    private static RecruitmentCandidate candidate(String uuid, CandidateStatus status) {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setUuid(uuid);
        candidate.setStatus(status);
        return candidate;
    }
}
