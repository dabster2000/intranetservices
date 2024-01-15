package dk.trustworks.intranet.dao.crm.services;

import dk.trustworks.intranet.dao.crm.model.Task;
import dk.trustworks.intranet.dao.workservice.model.Work;
import io.quarkus.panache.common.Sort;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class TaskService {

    public List<Task> listAll() {
        return Task.streamAll(Sort.ascending("name")).map(p -> (Task) p).collect(Collectors.toList());
    }

    public Task findByUuid(String uuid) {
        return Task.findById(uuid);
    }

    @Transactional
    public Task save(Task task) {
        task.setUuid(UUID.randomUUID().toString());
        task.persist();
        return task;
    }

    @Transactional
    public void updateOne(Task task) {
        Task.update("name = ?1, " +
                        "type = ?2, " +
                        "WHERE uuid like ?3 ",
                task.getName(), task.getType(), task.getUuid());
    }

    @Transactional
    public void delete(String uuid) {
        if(Work.find("taskuuid like ?1", uuid).count() > 0) return;
        Task.deleteById(uuid);
    }
}