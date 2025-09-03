package dk.trustworks.intranet.services;

import dk.trustworks.intranet.model.TaskboardItem;
import dk.trustworks.intranet.model.TaskboardItemWorker;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class TaskboardService {

    public List<TaskboardItem> findAll() {
        return TaskboardItem.listAll();
    }

    public Set<String> findAllBadges() {
        Set<String> badges = new TreeSet<>();
        badges.add("Forefront");
        badges.add("Internt kursus");
        badges.add("Markedsføring");
        badges.add("Operations");
        badges.add("Socialt årshjul");
        badges.add("Vidensdag");
        badges.add("Vidensdeling");
        badges.add("Technology");
        Stream<TaskboardItem> stream = TaskboardItem.<TaskboardItem>listAll().stream();
        stream.forEach(t -> {
            badges.addAll(Arrays.asList(t.getBadges().split(",")));
        });
        return badges.stream().map(String::trim).collect(Collectors.toUnmodifiableSet());
    }

    @Transactional
    public void persistTaskboardItem(TaskboardItem item) {
        if(item.getUuid()==null) {
            item.setUuid(UUID.randomUUID().toString());
            TaskboardItem.persist(item);
        } else {
            updateTaskboardItem(item);
        }
    }

    @Transactional
    public void updateTaskboardItem(TaskboardItem item) {
        System.out.println("TaskboardService.updateTaskboardItem");
        System.out.println("item = " + item);
        TaskboardItem.update("title = ?1, " +
                "description = ?2, " +
                "badges = ?3, " +
                "businesscase = ?4, " +
                "stakeholders = ?5, " +
                "originator = ?6, " +
                "expectedtime = ?7, " +
                "deadline = ?8, " +
                "status = ?9, " +
                "statusDate = ?10 " +
                "where uuid like ?11",
                item.getTitle(), item.getDescription(),
                item.getBadges(), item.getBusinesscase(),
                item.getStakeholders(), item.getOriginator(),
                item.getExpectedtime(), item.getDeadline(),
                item.getStatus(), item.getStatusDate(),
                item.getUuid());
        TaskboardItemWorker.delete("item = ?1", item);
        TaskboardItemWorker.flush();
        for (User worker : item.getWorkers()) {
            TaskboardItemWorker.persist(new TaskboardItemWorker(item, worker));
            TaskboardItemWorker.flush();
        }
    }
}

/*
private String uuid;
    private String taskboarduuid;
    private String titel;
    private String description;
    private String badges;
    private String businesscase;
    private String stakeholders;
    @ManyToOne(fetch = EAGER)
    @JoinColumn(name = "originator")
    private User originator;
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate deadline;
    @Enumerated(EnumType.STRING)
    private TaskboardItemStatus status;
    @OneToMany(mappedBy = "item", fetch = EAGER)
    @Fetch(FetchMode.SELECT)
    List<TaskboardItemChecklist> checklist;
    @OneToMany(mappedBy = "item", fetch = EAGER)
    List<TaskboardItemWorker> workers;
 */