package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.userservice.model.TransportationRegistration;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@JBossLog
@ApplicationScoped
public class TransportationRegistrationService {

    public List<TransportationRegistration> listAll(String useruuid) {
        return findByUseruuid(useruuid);
    }

    public List<TransportationRegistration> findByUseruuid(String useruuid) {
        return TransportationRegistration.findByUseruuid(useruuid);
    }

    public List<TransportationRegistration> findByUseruuidAndUnpaidAndMonth(String useruuid, LocalDate month) {
        return TransportationRegistration.findByUseruuidAndUnpaidAndMonth(useruuid, month);
    }

    public List<TransportationRegistration> findByUseruuidAndPaidOutMonth(String useruuid, LocalDate month) {
        return TransportationRegistration.findByUseruuidAndPaidOutMonth(useruuid, month);
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void persistOrUpdate(@Valid TransportationRegistration entity) {
        if(entity.getUuid()==null || entity.getUseruuid()==null) return;
        Optional<TransportationRegistration> existingEntity = TransportationRegistration.findByIdOptional(entity.getUuid());
        existingEntity.ifPresentOrElse(s -> {
            if(s.isPaidOut()) return;
            s.setDate(entity.getDate());
            s.setKilometers(entity.getKilometers());
            s.setDestination(entity.getDestination());
            s.setPurpose(entity.getPurpose());
            s.setPaidOut(entity.getPaidOut());
            updateTransportationAllowance(s);
        }, entity::persist);
    }

    private void updateTransportationAllowance(TransportationRegistration entity) {
        TransportationRegistration.update("date = ?1, " +
                        "kilometers = ?2, " +
                        "destination = ?3, " +
                        "purpose = ?4, " +
                        "paidOut = ?5 " +
                        "WHERE uuid LIKE ?6 ",
                entity.getDate(),
                entity.getKilometers(),
                entity.getDestination(),
                entity.getPurpose(),
                entity.getPaidOut(),
                entity.getUuid());
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void delete(String uuid) {
        if(TransportationRegistration.<TransportationRegistration>findById(uuid).isPaidOut()) return;
        TransportationRegistration.deleteById(uuid);
    }

    @Transactional
    public void setPaidAndUpdate(TransportationRegistration transportationRegistration) {
        transportationRegistration.setPaidOut(LocalDateTime.now());
        TransportationRegistration.update("paidOut = ?1 WHERE uuid like ?2 ", transportationRegistration.getPaidOut(), transportationRegistration.getUuid());
    }

    @Transactional
    public void clearPaidAndUpdate(TransportationRegistration transportationRegistration) {
        transportationRegistration.setPaidOut(null);
        TransportationRegistration.update("paidOut = ?1 WHERE uuid like ?2 ", transportationRegistration.getPaidOut(), transportationRegistration.getUuid());
    }
}