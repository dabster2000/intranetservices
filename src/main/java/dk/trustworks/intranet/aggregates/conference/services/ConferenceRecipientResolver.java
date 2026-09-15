package dk.trustworks.intranet.aggregates.conference.services;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Resolves stable participant IDs, never append-only snapshot UUIDs or browser addresses. */
@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class ConferenceRecipientResolver {
    private static final int MAX_PARTICIPANTS = 2000;
    private final DataSource dataSource;

    @Inject
    public ConferenceRecipientResolver(AgroalDataSource dataSource) {
        this((DataSource) dataSource);
    }

    ConferenceRecipientResolver(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public ResolvedRecipient resolveOne(String conferenceUuid, String participantUuid) {
        if (participantUuid == null) throw ConferenceUnsubscribeService.invalid("INVALID_RECIPIENT");
        return resolve(conferenceUuid, List.of(participantUuid)).getFirst();
    }

    public List<ResolvedRecipient> resolve(String conferenceUuid, List<String> participantUuids) {
        String list = ConferenceUnsubscribeService.requireUuid(conferenceUuid);
        if (participantUuids == null || participantUuids.isEmpty() || participantUuids.size() > MAX_PARTICIPANTS) {
            throw ConferenceUnsubscribeService.invalid("INVALID_RECIPIENT_SELECTION");
        }
        TreeSet<String> ids = participantUuids.stream().map(ConferenceUnsubscribeService::requireUuid)
                .collect(Collectors.toCollection(TreeSet::new));
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (var query = connection.prepareStatement("SELECT uuid FROM conferences WHERE uuid = ?")) {
                query.setString(1, list);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw ConferenceUnsubscribeService.invalid("INVALID_CONFERENCE");
                }
            }
            String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            try (var query = connection.prepareStatement("""
                    SELECT uuid, participantuuid, email, name, registered
                    FROM conference_participants WHERE conferenceuuid = ? AND participantuuid IN (
                    """ + placeholders + ")")) {
                query.setString(1, list);
                int index = 2;
                for (String id : ids) query.setString(index++, id);
                List<Snapshot> snapshots = new ArrayList<>();
                try (var rows = query.executeQuery()) {
                    while (rows.next()) snapshots.add(new Snapshot(rows.getString(1), rows.getString(2),
                            rows.getString(3), rows.getString(4), rows.getObject(5, LocalDateTime.class)));
                }
                return resolveSnapshots(ids.stream().toList(), snapshots);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("CONFERENCE_RECIPIENT_LOOKUP_UNAVAILABLE");
        }
    }

    static List<ResolvedRecipient> resolveSnapshots(List<String> ids, List<Snapshot> snapshots) {
        Map<String, List<Snapshot>> byParticipant = snapshots.stream()
                .collect(Collectors.groupingBy(s -> s.participantUuid().toLowerCase(java.util.Locale.ROOT)));
        Map<String, ResolvedRecipient> distinctAddresses = new LinkedHashMap<>();
        // Sorting BEFORE address dedup is the contract for deterministic personalized names.
        for (String id : new TreeSet<>(ids)) {
            List<Snapshot> history = byParticipant.get(id);
            if (history == null || history.isEmpty()) throw ConferenceUnsubscribeService.invalid("INVALID_RECIPIENT");
            Comparator<LocalDateTime> dateOrder = Comparator.nullsFirst(Comparator.naturalOrder());
            LocalDateTime newest = history.stream().map(Snapshot::registered).filter(java.util.Objects::nonNull).max(dateOrder).orElse(null);
            List<Snapshot> latest = history.stream().filter(s -> dateOrder.compare(s.registered(), newest) == 0).toList();
            List<String> addresses = latest.stream().map(s -> ConferenceUnsubscribeService.normalizeEmail(s.email())).distinct().toList();
            if (addresses.size() != 1) {
                throw new WebApplicationException(Response.status(409)
                        .entity(Map.of("error", "PARTICIPANT_SNAPSHOT_CONFLICT")).build());
            }
            Snapshot chosen = latest.stream().min(Comparator.comparing(Snapshot::snapshotUuid)).orElseThrow();
            String normalized = addresses.getFirst();
            distinctAddresses.putIfAbsent(normalized, new ResolvedRecipient(id,
                    ConferenceUnsubscribeService.validatedEmail(chosen.email()), normalized, chosen.name()));
        }
        return List.copyOf(distinctAddresses.values());
    }

    record Snapshot(String snapshotUuid, String participantUuid, String email, String name, LocalDateTime registered) {}
    public record ResolvedRecipient(String participantUuid, String email, String normalizedEmail, String name) {}
}
