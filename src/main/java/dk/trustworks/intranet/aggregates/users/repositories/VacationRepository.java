package dk.trustworks.intranet.aggregates.users.repositories;

import dk.trustworks.intranet.userservice.model.Vacation;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class VacationRepository implements PanacheRepository<Vacation> {
}