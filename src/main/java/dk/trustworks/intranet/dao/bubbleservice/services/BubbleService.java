package dk.trustworks.intranet.dao.bubbleservice.services;

import com.slack.api.methods.SlackApiException;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.dao.bubbleservice.model.Bubble;
import dk.trustworks.intranet.dao.bubbleservice.model.BubbleMember;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.services.UserService;
import io.quarkus.panache.common.Sort;
import io.quarkus.scheduler.Scheduled;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.transaction.Transactional;
import javax.ws.rs.DELETE;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class BubbleService {

    @Inject
    UserService userService;

    @Inject
    SlackService slackService;

    @Inject
    EntityManager entityManager;

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
    public void save(Bubble bubble) throws SlackApiException, IOException {
        if(bubble.getUuid() == null || bubble.getUuid().equalsIgnoreCase("")) bubble.setUuid(UUID.randomUUID().toString());
        if(bubble.getSlackChannelName().isEmpty()) throw new IOException("No bubble name");
        log.info("Creating slack channel: "+bubble.getSlackChannelName());
        bubble.setSlackchannel(slackService.createChannel("b_"+bubble.getSlackChannelName()));
        log.info("Created slack channel id "+bubble.getSlackchannel());
        log.info("Persisting bubble "+bubble);
        bubble.persistAndFlush();
        //entityManager.flush();
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
        addBubbleMember(bubble, useruuid);
    }

    @Transactional
    public void addBubbleMember(Bubble bubble, String useruuid) {
        log.info("BubbleService.addBubbleMember");
        log.info("bubbleuuid = " + bubble.getUuid() + ", useruuid = " + useruuid);
        if(bubble.getOwner().equals(useruuid)) return;
        if(bubble.getCoowner()!= null && bubble.getCoowner().equals(useruuid)) return;
        if(bubble.getBubbleMembers().stream().anyMatch(bubbleMember -> bubbleMember.getUseruuid().equals(useruuid))) return;
        BubbleMember bubbleMember = new BubbleMember(UUID.randomUUID().toString(), useruuid, bubble);
        slackService.addUserToChannel(userService.findById(useruuid, true), bubble.getSlackchannel());
        BubbleMember.persist(bubbleMember);
    }

    public void applyForBubble(String bubbleuuid, String useruuid) {
        Bubble bubble = Bubble.findById(bubbleuuid);
        User owner = userService.findById(bubble.getOwner(), true);
        try {
            slackService.sendMessage(owner, "Hi "+owner.getFirstname()+", *"+userService.findById(useruuid, true).getUsername()+"* would like to join your bubble "+bubble.getName()+"!");
        } catch (SlackApiException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public void removeBubbleMember(String bubbleuuid, String useruuid) {
        log.info("BubbleService.removeBubbleMember");
        log.info("bubbleuuid = " + bubbleuuid + ", useruuid = " + useruuid);
        Bubble bubble = Bubble.findById(bubbleuuid);
        slackService.removeUserFromChannel(userService.findById(useruuid, true), bubble.getSlackchannel());
        BubbleMember.delete("bubbleuuid like ?1 and useruuid like ?2", bubbleuuid, useruuid);
    }

    @Transactional
    public void removeBubbleMembers(String bubbleuuid) {
        BubbleMember.delete("bubbleuuid like ?1", bubbleuuid);
    }

    @Scheduled(every = "10m")
    public void cleanBubbles() {
        List<User> users = userService.findCurrentlyEmployedUsers(ConsultantType.STUDENT, ConsultantType.CONSULTANT, ConsultantType.STAFF);
        for (Bubble bubble : findAll()) {
            for (BubbleMember bubbleMember : bubble.getBubbleMembers()) {
                Optional<User> optionalUser = users.stream().filter(user -> user.getUuid().equals(bubbleMember.getUseruuid())).findAny();
                if(optionalUser.isEmpty()) removeBubbleMember(bubble.getUuid(), bubbleMember.getUseruuid());
                    //System.out.println("Remove bubbleMember " + userService.findById(bubbleMember.getUseruuid(), true).getUsername() + " from "+bubble.getName());
            }
        }
    }
}
