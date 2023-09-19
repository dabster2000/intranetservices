package dk.trustworks.intranet.communicationsservice.events;

import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.dao.crm.services.ProjectService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import io.quarkus.runtime.Startup;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@Startup
@JBossLog
@ApplicationScoped
public class WorkHandler {

    @ConfigProperty(name = "slack.motherSlackBotToken")
    String motherSlackBotToken;

    @Inject
    UserService userAPI;

    @Inject
    ClientService clientService;

    @Inject
    ProjectService projectService;

    /*
    @ConsumeEvent(value = "delete.work", blocking = true)
    public void handleDeleteWork(Work work) {
        log.info("WorkHandler.handleDeleteWork");
        log.info("work = " + work);

        Slack slack = Slack.getInstance();

        MethodsClient methods = slack.methods(motherSlackBotToken);

        ChatPostMessageRequest request = ChatPostMessageRequest.builder()
                .channel("U036JELTN")
                .text("Deleted: "+work.toString())
                .build();

        try {
            methods.chatPostMessage(request);
        } catch (IOException | SlackApiException e) {
            log.error(e);
        }
    }

     */

    /*
    @Incoming("work-no-contract")
    public void handeWorkWithNoContract(ProjectUserDateDTO projectUserDateDTO) throws SlackApiException, IOException {
        if(dateIt(projectUserDateDTO.getDate()).isBefore(LocalDate.now().minusYears(1))) return;
        log.warn("Sending work error message related to: "+projectUserDateDTO);
        final User user = userAPI.findUserByUuid(projectUserDateDTO.getUseruuid(), true);
        if(user == null) log.info("Could not find user for: "+projectUserDateDTO);
        assert user != null;
        final Project project = projectService.findByUuid(projectUserDateDTO.getProjectuuid());
        final Client client = clientService.findByUuid(project.getClientuuid());
        log.info("client = " + client);
        if(client.getUuid().equals("40c93307-1dfa-405a-8211-37cbda75318b")) return;
        final User clientManager = userAPI.findUserByUuid(client.getAccountmanager()!=null && !client.getAccountmanager().equals("") ? client.getAccountmanager() : "7948c5e8-162c-4053-b905-0f59a21d7746", true);

        //if(!clientManager.getUsername().equals("hans.lassen")) return;

        Slack slack = Slack.getInstance();

        ChatPostMessageResponse response = slack.methods(motherSlackBotToken).chatPostMessage(req -> req
                .channel(clientManager.getSlackusername())
                .blocks(asBlocks(
                        header(header -> header.text(plainText("Work without contract"))),
                        section(section -> section.fields(
                                asSectionFields(
                                        markdownText("*Consultant:* "+user.getFirstname()+" "+user.getLastname()),
                                        markdownText("*Date:* "+projectUserDateDTO.getDate())
                                )
                        )),
                        section(section -> section.fields(
                                asSectionFields(
                                        markdownText("*Client:* "+client.getName()),
                                        markdownText("*Project:* "+project.getName())
                                )
                        ))
                )));
        log.info("Reply was: "+response.getMessage());
    }

     */

}
/*
{
	"blocks": [
		{
			"type": "header",
			"text": {
				"type": "plain_text",
				"text": "Work without contract",
				"emoji": false
			}
		},
		{
			"type": "section",
			"fields": [
				{
					"type": "mrkdwn",
					"text": "*Consultant:*\nHans"
				},
				{
					"type": "mrkdwn",
					"text": "*Date:*\n22.1.2021"
				}
			]
		},
		{
			"type": "section",
			"fields": [
				{
					"type": "mrkdwn",
					"text": "*Customer:*\nAP Pension"
				},
				{
					"type": "mrkdwn",
					"text": "*Project:*\nBla bla"
				}
			]
		},
		{
			"type": "section",
			"fields": [
				{
					"type": "mrkdwn",
					"text": "*Hours:*\n16.0"
				}
			]
		}
	]
}
 */