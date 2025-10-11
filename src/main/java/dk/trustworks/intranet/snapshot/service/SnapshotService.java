package dk.trustworks.intranet.snapshot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.snapshot.exceptions.DataIntegrityException;
import dk.trustworks.intranet.snapshot.exceptions.SnapshotException;
import dk.trustworks.intranet.snapshot.model.ImmutableSnapshot;
import dk.trustworks.intranet.snapshot.repository.ImmutableSnapshotRepository;
import dk.trustworks.intranet.snapshot.strategy.SnapshotStrategy;
import dk.trustworks.intranet.snapshot.strategy.SnapshotStrategyRegistry;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for managing immutable snapshots.
 * Provides business logic for creating, retrieving, and validating snapshots
 * across different entity types.
 */
@ApplicationScoped
public class SnapshotService {

    @Inject
    ImmutableSnapshotRepository repository;

    @Inject
    SnapshotStrategyRegistry strategyRegistry;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Create a new snapshot for an entity.
     * Automatically determines version number and validates data.
     *
     * @param entityType entity type discriminator
     * @param entityId   business entity identifier
     * @param data       entity data to snapshot
     * @param lockedBy   username or email of person creating snapshot
     * @return the created snapshot
     * @throws WebApplicationException if validation fails or strategy not found
     */
    @Transactional
    public ImmutableSnapshot createSnapshot(
            String entityType, String entityId, Object data, String lockedBy) {

        // Get strategy for entity type
        SnapshotStrategy strategy = strategyRegistry.getStrategy(entityType);

        // Validate before snapshot
        strategy.validateBeforeSnapshot(data);

        // Get next version number
        int nextVersion = repository.getNextVersion(entityType, entityId);

        // Serialize to JSON
        String json = strategy.serializeToJson(data);

        // Extract metadata
        Map<String, String> metadataMap = strategy.extractMetadata(data);
        String metadata = null;
        if (!metadataMap.isEmpty()) {
            try {
                metadata = objectMapper.writeValueAsString(metadataMap);
            } catch (JsonProcessingException e) {
                Log.warnf("Failed to serialize metadata for %s:%s - continuing without metadata",
                    entityType, entityId);
            }
        }

        // Create snapshot entity
        ImmutableSnapshot snapshot = new ImmutableSnapshot();
        snapshot.setEntityType(entityType);
        snapshot.setEntityId(entityId);
        snapshot.setSnapshotVersion(nextVersion);
        snapshot.setSnapshotData(json);
        snapshot.setChecksum(calculateChecksum(json));
        snapshot.setMetadata(metadata);
        snapshot.setLockedBy(lockedBy);
        snapshot.setLockedAt(LocalDateTime.now());

        // Persist
        repository.save(snapshot);

        Log.infof("Created snapshot: %s v%d by %s with checksum %s",
            snapshot.getNaturalKey(), nextVersion, lockedBy, snapshot.getChecksum());

        return snapshot;
    }

    /**
     * Get latest snapshot for an entity.
     * Validates checksum before returning.
     *
     * @param entityType entity type
     * @param entityId   entity ID
     * @return optional snapshot if found and valid
     * @throws DataIntegrityException if checksum validation fails
     */
    public Optional<ImmutableSnapshot> getLatestSnapshot(String entityType, String entityId) {
        Optional<ImmutableSnapshot> snapshot = repository.findLatestByEntityTypeAndId(entityType, entityId);

        snapshot.ifPresent(this::validateChecksum);

        return snapshot;
    }

    /**
     * Get specific snapshot version.
     * Validates checksum before returning.
     *
     * @param entityType      entity type
     * @param entityId        entity ID
     * @param snapshotVersion version number
     * @return optional snapshot if found and valid
     * @throws DataIntegrityException if checksum validation fails
     */
    public Optional<ImmutableSnapshot> getSnapshot(
            String entityType, String entityId, Integer snapshotVersion) {

        Optional<ImmutableSnapshot> snapshot =
            repository.findByEntityTypeAndIdAndVersion(entityType, entityId, snapshotVersion);

        snapshot.ifPresent(this::validateChecksum);

        return snapshot;
    }

    /**
     * Get all versions of an entity.
     * Validates checksums for all snapshots.
     *
     * @param entityType entity type
     * @param entityId   entity ID
     * @return list of all snapshot versions (latest first)
     * @throws DataIntegrityException if any checksum validation fails
     */
    public List<ImmutableSnapshot> getAllVersions(String entityType, String entityId) {
        List<ImmutableSnapshot> snapshots = repository.findByEntityTypeAndId(entityType, entityId);

        // Validate all checksums
        for (ImmutableSnapshot snapshot : snapshots) {
            validateChecksum(snapshot);
        }

        return snapshots;
    }

    /**
     * Check if any snapshot exists for an entity.
     *
     * @param entityType entity type
     * @param entityId   entity ID
     * @return true if at least one snapshot exists
     */
    public boolean isSnapshotted(String entityType, String entityId) {
        return repository.existsByEntityTypeAndId(entityType, entityId);
    }

    /**
     * Get all snapshots for an entity type.
     *
     * @param entityType entity type
     * @return list of snapshots
     */
    public List<ImmutableSnapshot> findByEntityType(String entityType) {
        return repository.findByEntityType(entityType);
    }

    /**
     * Get all snapshots for an entity type with pagination.
     *
     * @param entityType entity type
     * @param page       page number (0-based)
     * @param size       page size
     * @return list of snapshots
     */
    public List<ImmutableSnapshot> findByEntityType(String entityType, int page, int size) {
        return repository.findByEntityType(entityType, page, size);
    }

    /**
     * Get all snapshots with pagination.
     *
     * @param page page number (0-based)
     * @param size page size
     * @return list of snapshots
     */
    public List<ImmutableSnapshot> findAll(int page, int size) {
        return repository.findAll(page, size);
    }

    /**
     * Get all snapshots.
     *
     * @return list of all snapshots
     */
    public List<ImmutableSnapshot> findAll() {
        return repository.findAllOrderByCreatedAtDesc();
    }

    /**
     * Find snapshots created by a specific user.
     *
     * @param lockedBy username or email
     * @return list of snapshots
     */
    public List<ImmutableSnapshot> findByLockedBy(String lockedBy) {
        return repository.findByLockedBy(lockedBy);
    }

    /**
     * Find snapshots created after a specific date.
     *
     * @param date date threshold
     * @return list of snapshots
     */
    public List<ImmutableSnapshot> findLockedAfter(LocalDateTime date) {
        return repository.findLockedAfter(date);
    }

    /**
     * Delete specific snapshot version.
     * DANGEROUS: Breaks audit trail. Should only be used in exceptional circumstances.
     *
     * @param entityType      entity type
     * @param entityId        entity ID
     * @param snapshotVersion version number
     * @return true if deleted, false if not found
     */
    @Transactional
    public boolean deleteSnapshot(String entityType, String entityId, Integer snapshotVersion) {
        boolean deleted = repository.deleteByEntityTypeAndIdAndVersion(entityType, entityId, snapshotVersion);

        if (deleted) {
            Log.warnf("DELETED snapshot: %s:%s:v%d - audit trail broken!",
                entityType, entityId, snapshotVersion);
        }

        return deleted;
    }

    /**
     * Delete all versions of an entity.
     * DANGEROUS: Breaks audit trail.
     *
     * @param entityType entity type
     * @param entityId   entity ID
     * @return number of snapshots deleted
     */
    @Transactional
    public long deleteAllVersions(String entityType, String entityId) {
        long deleted = repository.deleteByEntityTypeAndId(entityType, entityId);

        if (deleted > 0) {
            Log.warnf("DELETED %d snapshot versions for %s:%s - audit trail broken!",
                deleted, entityType, entityId);
        }

        return deleted;
    }

    /**
     * Validate checksum of a snapshot.
     *
     * @param snapshot snapshot to validate
     * @throws DataIntegrityException if checksum doesn't match
     */
    public void validateChecksum(ImmutableSnapshot snapshot) {
        String calculated = calculateChecksum(snapshot.getSnapshotData());

        if (!calculated.equals(snapshot.getChecksum())) {
            String message = String.format(
                "Checksum validation failed for %s. Expected: %s, Got: %s",
                snapshot.getNaturalKey(), snapshot.getChecksum(), calculated);

            Log.errorf(message);
            throw new DataIntegrityException(message);
        }
    }

    /**
     * Calculate SHA-256 checksum for data.
     *
     * @param data the data string
     * @return hex-encoded checksum
     */
    public String calculateChecksum(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));

            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new SnapshotException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Get statistics for an entity type.
     *
     * @param entityType entity type
     * @return count of snapshots
     */
    public long countByEntityType(String entityType) {
        return repository.countByEntityType(entityType);
    }

    /**
     * Get all registered entity types.
     *
     * @return set of entity types
     */
    public java.util.Set<String> getRegisteredEntityTypes() {
        return strategyRegistry.getRegisteredEntityTypes();
    }
}
