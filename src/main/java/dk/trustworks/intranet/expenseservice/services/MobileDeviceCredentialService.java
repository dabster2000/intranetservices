package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.dto.CreateDeviceCredentialRequest;
import dk.trustworks.intranet.expenseservice.dto.DeviceCredentialDTO;
import dk.trustworks.intranet.expenseservice.model.MobileDeviceCredential;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class MobileDeviceCredentialService {

    private DeviceCredentialDTO toDto(MobileDeviceCredential e) {
        return new DeviceCredentialDTO(
            e.uuid, e.userUuid, e.credentialId, e.publicKey, e.signCount,
            e.deviceLabel, e.transports, e.credentialEpoch,
            e.createdAt, e.lastUsedAt, e.revokedAt,
            e.createdAt.toEpochSecond(java.time.ZoneOffset.UTC));
    }

    /** Active (non-revoked) credentials for a user. */
    public List<DeviceCredentialDTO> listActiveForUser(String userUuid) {
        return MobileDeviceCredential.<MobileDeviceCredential>find(
                "userUuid = ?1 and revokedAt is null", userUuid)
            .stream().map(this::toDto).toList();
    }

    /** Lookup by Base64URL credential id (used during assertion verification). */
    public Optional<DeviceCredentialDTO> findByCredentialId(String credentialId) {
        return MobileDeviceCredential.<MobileDeviceCredential>find("credentialId", credentialId)
            .firstResultOptional().map(this::toDto);
    }

    @Transactional
    public DeviceCredentialDTO create(CreateDeviceCredentialRequest req) {
        MobileDeviceCredential e = new MobileDeviceCredential();
        e.userUuid     = req.userUuid();
        e.credentialId = req.credentialId();
        e.publicKey    = req.publicKey();
        e.signCount    = req.signCount();
        e.deviceLabel  = req.deviceLabel();
        e.transports   = req.transports();
        e.credentialEpoch = 0;
        e.persist();
        return toDto(e);
    }

    /** Update the WebAuthn counter and stamp last_used_at after a successful unlock. */
    @Transactional
    public Optional<DeviceCredentialDTO> updateCounter(String credentialId, long signCount) {
        Optional<MobileDeviceCredential> found = MobileDeviceCredential
            .<MobileDeviceCredential>find("credentialId", credentialId).firstResultOptional();
        found.ifPresent(e -> {
            e.signCount   = signCount;
            e.lastUsedAt  = LocalDateTime.now();
        });
        return found.map(this::toDto);
    }

    /** Revoke a credential: stamp revoked_at and bump the epoch so any live cookie dies on next refresh. */
    @Transactional
    public boolean revoke(String credentialId) {
        Optional<MobileDeviceCredential> found = MobileDeviceCredential
            .<MobileDeviceCredential>find("credentialId", credentialId).firstResultOptional();
        found.ifPresent(e -> {
            e.revokedAt = LocalDateTime.now();
            e.credentialEpoch = e.credentialEpoch + 1;
        });
        return found.isPresent();
    }
}
