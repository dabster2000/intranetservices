// Example of an EmailService tool method
package dk.trustworks.intranet.ai;

import dev.langchain4j.agent.tool.Tool;
import io.quarkus.logging.Log;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class EmailService {

    @Inject
    Mailer mailer;

    @Tool("send the provided content via email")
    public void sendAnEmail(String content) {
        Log.info("Sending an email: " + content);
        //mailer.send(Mail.withText("sendMeALetter@quarkus.io", "A poem for you", content));
    }

}