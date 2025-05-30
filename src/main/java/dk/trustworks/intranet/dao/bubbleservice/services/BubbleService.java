package dk.trustworks.intranet.dao.bubbleservice.services;

import com.slack.api.methods.SlackApiException;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.dao.bubbleservice.model.Bubble;
import dk.trustworks.intranet.dao.bubbleservice.model.BubbleMember;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import lombok.extern.jbosslog.JBossLog;

import java.io.IOException;
import java.time.LocalDate;
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

    public List<Bubble> findAll() {
        return Bubble.findAll().list();
    }

    public List<Bubble> findAll(String ownerUseruuid) {
        return Bubble.find("owner like ?1 AND active = true", ownerUseruuid).list();
    }

    public Bubble findByUUID(String uuid) {
        return Bubble.findById(uuid);
    }

    public List<Bubble> findBubblesByActiveTrueOrderByCreatedDesc() {
        return Bubble.find("active = true", Sort.descending("created")). list();
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
        bubble.setSlackchannel(slackService.createChannel(bubble.getType().getSlackPrefix()+bubble.getSlackChannelName()));
        bubble.setCreated(LocalDate.now());
        bubble.setActive(true);
        bubble.persist();
    }

    @Transactional
    public void update(Bubble bubble) throws SlackApiException, IOException {
        log.info("Updating bubble: " + bubble);
        if(!bubble.isActive()) {
            slackService.closeChannel(bubble.getSlackchannel());
        }
        Bubble.update("name = ?1, " +
                        "description = ?2, " +
                        "application = ?3, " +
                        "slackchannel = ?4, " +
                        "owner = ?5, " +
                        "coowner = ?6, " +
                        "meetingform = ?7, " +
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
    public void delete(String bubbleuuid) throws SlackApiException, IOException {
        slackService.closeChannel(Bubble.findById(bubbleuuid).getSlackchannel());
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
        if(BubbleMember.find("useruuid like ?1 and bubble = ?2", useruuid, bubble).singleResultOptional().isPresent()) return;
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
        BubbleMember.delete("bubble = ?1 and useruuid like ?2", bubble, useruuid);
    }

    @Transactional
    public void removeBubbleMembers(String bubbleuuid) {
        BubbleMember.delete("bubble = ?1", Bubble.findById(bubbleuuid));
    }

    //@Scheduled(every = "10m")
    public void cleanBubbles() {
        List<User> users = userService.findCurrentlyEmployedUsers(true, ConsultantType.STUDENT, ConsultantType.CONSULTANT, ConsultantType.STAFF);
        for (Bubble bubble : findAll()) {
            for (BubbleMember bubbleMember : bubble.getBubbleMembers()) {
                Optional<User> optionalUser = users.stream().filter(user -> user.getUuid().equals(bubbleMember.getUseruuid())).findAny();
                if(optionalUser.isEmpty()) removeBubbleMember(bubble.getUuid(), bubbleMember.getUseruuid());
                    //System.out.println("Remove bubbleMember " + userService.findById(bubbleMember.getUseruuid(), true).getUsername() + " from "+bubble.getName());
            }
        }
    }
}
