package dk.trustworks.intranet.communicationsservice.resources;

import dk.trustworks.intranet.dto.TrustworksMail;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.smallrye.common.annotation.Blocking;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@JBossLog
@ApplicationScoped
public class MailResource {

    @Inject
    Mailer mailer;

    @ConfigProperty(name = "quarkus.mailer.username")
    String username;

    @ConfigProperty(name = "quarkus.mailer.password")
    String password;

    @Blocking
    public void sendingHTML(TrustworksMail mail) {
        log.info("MailResource.sendingHTML");
        log.info("mail = " + mail);
        log.info("username = " + username);
        log.info("password = " + password);


        mailer.send(Mail.withHtml(mail.getTo(), mail.getSubject(), mail.getBody()));
    }
}