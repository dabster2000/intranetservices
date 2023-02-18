package dk.trustworks.intranet.dao.bubbleservice.services;

import dk.trustworks.intranet.dao.bubbleservice.model.Bubble;
import dk.trustworks.intranet.dao.bubbleservice.model.BubbleMember;
import io.quarkus.panache.common.Sort;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class BubbleService {

    public List<Bubble> findAll() {
        return Bubble.findAll().list();
    }

    public List<Bubble> findAll(String ownerUseruuid) {
        return Bubble.find("owner like ?1 AND active = true", ownerUseruuid).list();
    }

    public List<Bubble> findBubblesByActiveTrueOrderByCreatedDesc() {
        return Bubble.find("active = 1", Sort.descending("created")).list();
    }

    public List<Bubble> findActiveBubblesByUseruuid(String useruuid) {
        List<Bubble> result = new ArrayList<>();
        for (Bubble bubble : findBubblesByActiveTrueOrderByCreatedDesc()) {
            for (BubbleMember bubbleMember : bubble.getBubbleMembers()) {
                if(bubbleMember.getUseruuid().equals(useruuid)) result.add(bubble);
            }
        }
        return result;
    }

    @Transactional
    public void save(Bubble bubble) {
        if(bubble.getUuid() == null || bubble.getUuid().equalsIgnoreCase("")) bubble.setUuid(UUID.randomUUID().toString());
        Bubble.persist(bubble);
    }

    @Transactional
    public void update(Bubble bubble) {
        log.info("Updating bubble: " + bubble);
        Bubble.update("name = ?1, " +
                        "description = ?2, " +
                        "application = ?3, " +
                        "slackchannel = ?4, " +
                        "owner = ?5, " +
                        "co_owner = ?6, " +
                        "meeting_form = ?7, " +
                        "preconditions = ?8, " +
                        "active = ?9 " +
                        "WHERE uuid like ?10 ",
                bubble.getName(),
                bubble.getDescription(),
                bubble.getApplication(),
                bubble.getSlackchannel(),
                bubble.getOwner(),
                bubble.getCoowner(),
                bubble.getMeetingform(),
                bubble.getPreconditions(),
                bubble.isActive(),
                bubble.getUuid());
    }

    @DELETE
    @Transactional
    public void delete(String bubbleuuid) {
        Bubble.deleteById(bubbleuuid);
    }

    @Transactional
    public void addBubbleMember(String bubbleuuid, String useruuid) {
        Bubble bubble = Bubble.findById(bubbleuuid);
        if(bubble.getOwner().equals(useruuid)) return;
        if(bubble.getCoowner()!= null && bubble.getCoowner().equals(useruuid)) return;
        if(bubble.getBubbleMembers().stream().anyMatch(bubbleMember -> bubbleMember.getUseruuid().equals(useruuid))) return;
        BubbleMember bubbleMember = new BubbleMember(UUID.randomUUID().toString(), useruuid, bubble);
        BubbleMember.persist(bubbleMember);

        /*
        Bubble.findById(bubbleuuid).subscribe().with(bubble -> {
            if(bubble.getOwner().equals(useruuid)) return; // Stop is added user is owner
            if(bubble.getBubbleMembers().stream().anyMatch(bubbleMember -> bubbleMember.getUseruuid().equals(useruuid))) return; // Stop if added user is already member
            BubbleMember bubbleMember = new BubbleMember(UUID.randomUUID().toString(), useruuid, bubble);
            BubbleMember.persist(bubbleMember);
        });
         */
    }

    @Transactional
    public void removeBubbleMember(String bubbleuuid, String useruuid) {
        BubbleMember.delete("bubbleuuid like ?1 and useruuid like ?2", bubbleuuid, useruuid);
    }

    @Transactional
    public void removeBubbleMembers(String bubbleuuid) {
        BubbleMember.delete("bubbleuuid like ?1", bubbleuuid);
    }
}
