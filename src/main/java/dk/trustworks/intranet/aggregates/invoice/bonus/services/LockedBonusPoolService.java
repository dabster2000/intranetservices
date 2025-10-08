package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.LockedBonusPoolData;
import dk.trustworks.intranet.aggregates.invoice.bonus.repositories.LockedBonusPoolRepository;
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
import java.util.Optional;

/**
 * Service for managing locked bonus pool data.
 * Provides business logic for locking, retrieving, and validating bonus pool snapshots.
 */
@ApplicationScoped
public class LockedBonusPoolService {

    @Inject
    LockedBonusPoolRepository repository;

    /**
     * Find locked bonus data by fiscal year.
     *
     * @param fiscalYear the fiscal year
     * @return optional locked bonus data
     */
    public Optional<LockedBonusPoolData> findByFiscalYear(Integer fiscalYear) {
        return repository.findByFiscalYear(fiscalYear);
    }

    /**
     * Check if data is locked for a fiscal year.
     *
     * @param fiscalYear the fiscal year
     * @return true if locked
     */
    public boolean isLocked(Integer fiscalYear) {
        return repository.existsByFiscalYear(fiscalYear);
    }

    /**
     * Get all locked bonus data ordered by fiscal year descending.
     *
     * @return list of all locked data
     */
    public List<LockedBonusPoolData> findAll() {
        return repository.findAllOrderByFiscalYearDesc();
    }

    /**
     * Find locks created by a specific user.
     *
     * @param lockedBy username or email
     * @return list of locks
     */
    public List<LockedBonusPoolData> findByLockedBy(String lockedBy) {
        return repository.findByLockedBy(lockedBy);
    }

    /**
     * Find locks created after a specific date.
     *
     * @param date the date threshold
     * @return list of locks
     */
    public List<LockedBonusPoolData> findLockedAfter(LocalDateTime date) {
        return repository.findLockedAfter(date);
    }

    /**
     * Lock bonus pool data for a fiscal year.
     * Creates a new immutable snapshot with checksum verification.
     *
     * @param fiscalYear fiscal year to lock
     * @param poolContextJson JSON serialization of FiscalYearPoolContext
     * @param lockedBy username or email of person locking
     * @return the saved locked data
     * @throws WebApplicationException if fiscal year is already locked or validation fails
     */
    @Transactional
    public LockedBonusPoolData lockBonusPool(Integer fiscalYear, String poolContextJson, String lockedBy) {
        // Check if already locked
        if (repository.existsByFiscalYear(fiscalYear)) {
            Log.warnf("Attempted to lock already locked fiscal year %d by %s", fiscalYear, lockedBy);
            throw new WebApplicationException(
                Response.status(Response.Status.CONFLICT)
                    .entity(String.format("Fiscal year %d is already locked", fiscalYear))
                    .build()
            );
        }

        // Validate inputs
        if (poolContextJson == null || poolContextJson.trim().isEmpty()) {
            throw new WebApplicationException(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity("Pool context JSON cannot be empty")
                    .build()
            );
        }

        // Create entity
        LockedBonusPoolData data = new LockedBonusPoolData();
        data.fiscalYear = fiscalYear;
        data.poolContextJson = poolContextJson;
        data.lockedBy = lockedBy;
        data.lockedAt = LocalDateTime.now();
        data.checksum = calculateChecksum(poolContextJson);

        // Persist
        repository.save(data);

        Log.infof("Locked bonus pool data for fiscal year %d by %s with checksum %s",
                  fiscalYear, lockedBy, data.checksum);

        return data;
    }

    /**
     * Unlock and delete bonus pool data for a fiscal year.
     * Should only be used in exceptional circumstances as it breaks audit trail.
     *
     * @param fiscalYear the fiscal year to unlock
     * @return true if deleted, false if not found
     */
    @Transactional
    public boolean unlockBonusPool(Integer fiscalYear) {
        boolean deleted = repository.deleteByFiscalYear(fiscalYear);
        if (deleted) {
            Log.warnf("Unlocked (deleted) bonus pool data for fiscal year %d", fiscalYear);
        }
        return deleted;
    }

    /**
     * Validate checksum of locked data.
     *
     * @param data the locked data to validate
     * @return true if checksum matches
     */
    public boolean validateChecksum(LockedBonusPoolData data) {
        String calculatedChecksum = calculateChecksum(data.poolContextJson);
        boolean valid = calculatedChecksum.equals(data.checksum);

        if (!valid) {
            Log.errorf("Checksum validation failed for fiscal year %d. Expected: %s, Got: %s",
                      data.fiscalYear, data.checksum, calculatedChecksum);
        }

        return valid;
    }

    /**
     * Get locked data with checksum validation.
     *
     * @param fiscalYear the fiscal year
     * @return optional locked data if found and valid
     * @throws WebApplicationException if checksum validation fails
     */
    public Optional<LockedBonusPoolData> getValidatedData(Integer fiscalYear) {
        Optional<LockedBonusPoolData> dataOpt = repository.findByFiscalYear(fiscalYear);

        if (dataOpt.isPresent()) {
            LockedBonusPoolData data = dataOpt.get();
            if (!validateChecksum(data)) {
                throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(String.format("Data integrity check failed for fiscal year %d", fiscalYear))
                        .build()
                );
            }
        }

        return dataOpt;
    }

    /**
     * Calculate SHA-256 checksum for JSON data.
     *
     * @param data the JSON string
     * @return hex-encoded checksum
     */
    private String calculateChecksum(String data) {
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
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Delete all locked bonus pool data.
     * Use with extreme caution - destroys entire audit trail.
     */
    @Transactional
    public void deleteAll() {
        Log.warn("Deleting ALL locked bonus pool data - this destroys the audit trail!");
        repository.removeAll();
    }
}
