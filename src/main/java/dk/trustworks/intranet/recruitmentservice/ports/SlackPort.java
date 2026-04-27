package dk.trustworks.intranet.recruitmentservice.ports;

import dk.trustworks.intranet.recruitmentservice.ports.slack.SendDmCommand;

public interface SlackPort {
    void sendDirectMessage(SendDmCommand cmd);
}
