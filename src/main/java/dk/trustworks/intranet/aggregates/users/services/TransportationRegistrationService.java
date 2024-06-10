package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.userservice.model.TransportationRegistration;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.extern.jbosslog.JBossLog;

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

    public List<TransportationRegistration> findByUseruuidAndUnpaid(String useruuid) {
        return TransportationRegistration.findByUseruuidAndUnpaid(useruuid);
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void persistOrUpdate(@Valid TransportationRegistration entity) {
        if(entity.getUuid()==null || entity.getUseruuid()==null) return;
        Optional<TransportationRegistration> existingEntity = TransportationRegistration.findByIdOptional(entity.getUuid());
        existingEntity.ifPresentOrElse(s -> {
            if(s.getPaid()) return;
            s.setDate(entity.getDate());
            s.setKilometers(entity.getKilometers());
            s.setDestination(entity.getDestination());
            s.setPurpose(entity.getPurpose());
            s.setPaid(entity.isPaid());
            updateTransportationAllowance(s);
        }, entity::persist);
    }

    private void updateTransportationAllowance(TransportationRegistration entity) {
        TransportationRegistration.update("date = ?1, " +
                        "kilometers = ?2, " +
                        "destination = ?3, " +
                        "purpose = ?4, " +
                        "paid = ?5 " +
                        "WHERE uuid LIKE ?6 ",
                entity.getDate(),
                entity.getKilometers(),
                entity.getDestination(),
                entity.getPurpose(),
                entity.isPaid(),
                entity.getUuid());
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void delete(String uuid) {
        if(TransportationRegistration.<TransportationRegistration>findById(uuid).isPaid()) return;
        TransportationRegistration.deleteById(uuid);
    }
}