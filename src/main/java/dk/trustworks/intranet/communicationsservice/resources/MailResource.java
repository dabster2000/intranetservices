package dk.trustworks.intranet.communicationsservice.resources;

import dk.trustworks.intranet.communicationsservice.model.TrustworksMail;
import dk.trustworks.intranet.communicationsservice.model.enums.MailStatus;
import dk.trustworks.intranet.fileservice.resources.PhotoService;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.Blocking;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import java.util.Optional;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class MailResource {

    @Inject
    PhotoService photoService;

    @Inject
    Mailer mailer;

    @ConfigProperty(name = "quarkus.mailer.username")
    String username;

    @ConfigProperty(name = "quarkus.mailer.password")
    String password;

    @Blocking
    @Transactional
    public void sendingHTML(TrustworksMail mail) {
        log.info("MailResource.sendingHTML");
        log.info("mail = " + mail);
        log.info("username = " + username);
        log.info("password = " + password);

        mail.setStatus(MailStatus.READY);
        mail.persist();
    }

    @Transactional
    @Scheduled(every = "10s")
    public void sendMailJob() {
        Optional<TrustworksMail> optMail = TrustworksMail.find("status = ?1", MailStatus.READY).firstResultOptional();
        optMail.ifPresent(mail -> {
            log.info("Sending delayed email uuid: " + mail.getUuid());
            mailer.send(Mail.withHtml(mail.getTo(), mail.getSubject(), mail.getBody()));
            mail.setStatus(MailStatus.SENT);
            TrustworksMail.update("status = ?1 where uuid like ?2", mail.getStatus(), mail.getUuid());
        });
    }

    @Blocking
    @Transactional
    public void sendingWaitingListMail(String mailTo) {
        TrustworksMail mail = new TrustworksMail(UUID.randomUUID().toString(), mailTo, "BEKRÆFTELSE PÅ OPSKRIVNING",
                "<div style='width: 600px'>\n" +
                        "  <img src=\"cid:forefront@trustworks.dk\" />" +
                        "  <p>&nbsp;</p>\n" +
                        "  <p>Tusind tak for din interesse i FOREFRONT23.&nbsp;Du vil f&aring; besked hurtigst muligt, om du har f&aring;et en plads.</p>\n" +
                        "  <p>V&aelig;r opm&aelig;rksom p&aring; at FOREFRONT er en konference, som prim&aelig;rt henvender sig til folk, der arbejder med IT- og digital transformation. Arrang&oslash;ren bag, Trustworks, forbeholder sig derfor retten til at afvise tilmeldinger uden for m&aring;lgruppen.</p>\n" +
                        "  <p>Venligst,</p>\n" +
                        "  <p>Trustworks</p>\n" +
                        "  <p>&nbsp;</p>\n" +
                        "  <img src=\"cid:trustworks@trustworks.dk\" />" +
                        "  <p>&nbsp;</p>\n" +
                        "  <p><span style=\"font-size:10px\"><em>Hvis du har takket ja&nbsp;til at modtage e-mails&nbsp;med tilbud om kommende konferencer, men ikke l&aelig;ngere &oslash;nsker at modtage disse, bedes du&nbsp;skrive&nbsp;til&nbsp;<a href=\"“mailto:forefrontkonf@trustworks.dk”\">forefrontkonf@trustworks.dk</a>, s&aring; skal vi nok afmelde dig.&nbsp;Har du sp&oslash;rgsm&aring;l, som ikke er besvaret i vores <a href=\"“https://forefront.trustworks.dk/faq/”\">FAQ</a>, bedes du ligeledes kontakte os.&nbsp;</em></span></p>\n" +
                        "</div>");

        log.info("MailResource.sendingHTML");
        log.info("mail = " + mail);
        log.info("username = " + username);
        log.info("password = " + password);

        mailer.send(Mail.withHtml(
                mail.getTo(),
                mail.getSubject(),
                mail.getBody())
                        .addInlineAttachment("forefront-logo.png", photoService.findPhotoByRelatedUUID("c3395f9f-1d8d-476e-a517-c83e3b86545a").getFile(), "image/png", "<forefront@trustworks.dk>")
                        .addInlineAttachment("trustworks-logo.png", photoService.findPhotoByRelatedUUID("91af1119-7725-4309-8ae8-131463d8d23c").getFile(), "image/png", "<trustworks@trustworks.dk>")
        );
        mail.setStatus(MailStatus.SENT);
        mail.persist();
    }

    @Blocking
    @Transactional
    public void sendingInvitationMail(String mailTo) {
        TrustworksMail mail = new TrustworksMail(UUID.randomUUID().toString(), mailTo, "TILLYKKE, DU HAR FÅET EN PLADS",
                "<div style='width: 600px'>\n" +
                        "  <img src=\"cid:forefront@trustworks.dk\" />" +
                        "  <p>&nbsp;</p>\n" +
                        "<p>Vi ser frem til at byde dig velkommen til FOREFRONT den 28. september 2023 p&aring; Langelinie Pavillonen. Vi garanterer dig en sp&aelig;ndende dag i selskab med et hav af inspirerende opl&aelig;gsholdere inden for digital forretningsudvikling og innovation.</p>\n" +
                        "<p>I &aring;r er programmet centreret om kultur, processer og teknologi &ndash; lanceringen af det endelige program kan du l&oslash;bende holde &oslash;je med inde p&aring; forefront.trustworks.dk. Ligeledes kan du finde de nyeste talere inde p&aring; siden.</p>\n" +
                        "<p><strong>V&aelig;rd at vide:</strong></p>\n" +
                        "<ul>\n" +
                        "<li>Konferencen afholdes torsdag den 28. september 2023 kl. 09.00-17.00.</li>\n" +
                        "<li>Langelinie Pavillonen danner rammerne for dette &aring;rs FOREFRONT, og adressen er Langelinie 10, 2100 K&oslash;benhavn &Oslash;.</li>\n" +
                        "<li>Der vil blive s&oslash;rget for forplejning under konferencen &ndash; s&aring;fremt du har nogle allergener bedes du kontakte os via e-mail.</li>\n" +
                        "</ul>\n" +
                        "<p>Vi skal nok komme med flere oplysninger, n&aring;r vi n&aelig;rmer os konferencedagen. Mens du i sp&aelig;nding venter p&aring; startskuddet til FOREFRONT,&nbsp;kan du orientere dig p&aring;&nbsp;forefront.trustworks.dk.&nbsp;</p>\n" +
                        "<p>Har du sp&oslash;rgsm&aring;l, som ikke er besvaret i vores FAQ,&nbsp;er du velkommen til at kontakte os p&aring; <a href=\"&ldquo;mailto:forefrontkonf@trustworks.dk&rdquo;\">forefrontkonf@trustworks.dk</a>.</p>\n" +
                        "<p>&nbsp;</p>\n" +
                        "<p>Vi gl&aelig;der os til at se dig!</p>\n" +
                        "<p>&nbsp;</p>\n" +
                        "<p>Venligst,&nbsp;</p>\n" +
                        "<p>Trustworks</p>\n" +
                        "  <p>&nbsp;</p>\n" +
                        "  <img src=\"cid:trustworks@trustworks.dk\" />" +
                        "  <p>&nbsp;</p>\n" +
                        "  <p><span style=\"font-size:10px\"><em>Bliver du forhindret i at deltage, bedes du hurtigst muligt kontakte os, s&aring; pladsen kan g&aring; til anden side. Har du sp&oslash;rgsm&aring;l, som ikke er besvaret i vores FAQ</em><em>, bedes du ligeledes kontakte os p&aring; </em><a href=\"&ldquo;mailto:forefrontkonf@trustworks.dk&rdquo;\"><em>forefrontkonf@trustworks.dk</em></a><em>. </em></span></p>\n" +
                        "  <p><span style=\"font-size:10px\"><em>Hvis du har takket ja&nbsp;til at modtage e-mails&nbsp;med tilbud om kommende konferencer, men ikke l&aelig;ngere &oslash;nsker at modtage disse, bedes du&nbsp;skrive&nbsp;til os p&aring;&nbsp;</em><a href=\"&ldquo;mailto:forefrontkonf@trustworks.dk&rdquo;\"><em>forefrontkonf@trustworks.dk</em></a><em>., s&aring; skal vi nok afmelde dig.&nbsp;Har du sp&oslash;rgsm&aring;l, som ikke er besvaret i vores <a href=\"&ldquo;http://forefront.34.241.72.253.nip.io/faq/&rdquo;\">FAQ</a>, bedes du ligeledes kontakte os.</em></span></p>\n" +
                        "</div>");

        log.info("MailResource.sendingHTML");
        log.info("mail = " + mail);
        log.info("username = " + username);
        log.info("password = " + password);

        mailer.send(Mail.withHtml(
                        mail.getTo(),
                        mail.getSubject(),
                        mail.getBody())
                .addInlineAttachment("forefront-logo.png", photoService.findPhotoByRelatedUUID("c3395f9f-1d8d-476e-a517-c83e3b86545a").getFile(), "image/png", "<forefront@trustworks.dk>")
                .addInlineAttachment("trustworks-logo.png", photoService.findPhotoByRelatedUUID("91af1119-7725-4309-8ae8-131463d8d23c").getFile(), "image/png", "<trustworks@trustworks.dk>")
        );
        mail.setStatus(MailStatus.SENT);
        mail.persist();
    }

    @Blocking
    @Transactional
    public void sendingDenyMail(String mailTo) {
        TrustworksMail mail = new TrustworksMail(UUID.randomUUID().toString(), mailTo, "TUSIND TAK FOR DIN INTERESSE",
                "<div style='width: 600px'>\n" +
                        "  <img src=\"cid:forefront@trustworks.dk\" />" +
                        "  <p>&nbsp;</p>\n" +
                        "<p>Hej&nbsp;</p>\n" +
                        "<p>Du har desv&aelig;rre ikke f&aring;et en plads til dette &aring;rs FOREFRONT.&nbsp;Tusind tak for din interesse. </p>\n" +
                        "<p>Vi h&aring;ber, at du har lyst til at f&oslash;lge med p&aring; <a href=\"https://forefront.trustworks.dk\">vores hjemmeside</a> eller <a href=\"https://dk.linkedin.com/company/trustworks-as\">LinkedIn</a>, hvor vi vil dele h&oslash;jdepunkter og meget mere i forbindelse med konferencen. </p>\n" +
                        "<p>God dag. </p>\n" +
                        "<p>Venligst, </p>\n" +
                        "<p>Trustworks</p>\n" +
                        "  <p>&nbsp;</p>\n" +
                        "  <img src=\"cid:trustworks@trustworks.dk\" />" +
                        "  <p>&nbsp;</p>\n" +
                        "  <p><span style=\"font-size:10px\"><em>Hvis du har takket ja&nbsp;til at modtage e-mails&nbsp;med tilbud om kommende konferencer, men ikke l&aelig;ngere &oslash;nsker at modtage disse, bedes du&nbsp;skrive&nbsp;til&nbsp;forefrontkonf@trustworks.dk, s&aring; skal vi nok afmelde dig.&nbsp;Har du sp&oslash;rgsm&aring;l, som ikke er besvaret i vores <a href=\"http://forefront.trustworks.dk/faq/\">FAQ</a>, bedes du ligeledes kontakte os.</em></span></p>\n" +
                        "</div>");

        log.info("MailResource.sendingHTML");
        log.info("mail = " + mail);
        log.info("username = " + username);
        log.info("password = " + password);

        mailer.send(Mail.withHtml(
                        mail.getTo(),
                        mail.getSubject(),
                        mail.getBody())
                .addInlineAttachment("forefront-logo.png", photoService.findPhotoByRelatedUUID("c3395f9f-1d8d-476e-a517-c83e3b86545a").getFile(), "image/png", "<forefront@trustworks.dk>")
                .addInlineAttachment("trustworks-logo.png", photoService.findPhotoByRelatedUUID("91af1119-7725-4309-8ae8-131463d8d23c").getFile(), "image/png", "<trustworks@trustworks.dk>")
        );
        mail.setStatus(MailStatus.SENT);
        mail.persist();
    }

    @Blocking
    @Transactional
    public void sendingWithdrawMail(String mailTo) {
        TrustworksMail mail = new TrustworksMail(UUID.randomUUID().toString(), mailTo, "BEKRÆFTELSE PÅ AFMELDING",
                "<div style='width: 600px'>\n" +
                        "<img src=\"cid:forefront@trustworks.dk\" />" +
                        "<p>&nbsp;</p>\n" +
                        "<p>Hej&nbsp;</p>\n" +
                        "<p>Vi har nu modtaget din afmelding til FOREFRONT 2023 &ndash; vi h&aring;ber p&aring; at se dig til n&aelig;ste &aring;r!</p>\n" +
                        "<p>Skulle du fortsat have interesse i at se, hvordan konferencen udfolder sig, opfordrer vi dig til at f&oslash;lge med p&aring; <a href=\"https://forefront.trustworks.dk\">vores hjemmeside</a> eller <a href=\"https://dk.linkedin.com/company/trustworks-as\">LinkedIn</a>. </p>\n" +
                        "<p>Hvis du er interesseret i at modtage slides fra opl&aelig;ggene p&aring; konferencen, er du velkommen til at besvare denne e-mail. S&aring; sender vi dem din vej efter den 28. september.</p>\n" +
                        "<p>Vi h&aring;ber at kunne byde dig velkommen en anden gang.</p>\n" +
                        "<p>Venligst, </p>\n" +
                        "<p>Trustworks</p>\n" +
                        "<p>&nbsp;</p>\n" +
                        "<img src=\"cid:trustworks@trustworks.dk\" />" +
                        "<p>&nbsp;</p>\n" +
                        "<p><span style=\"font-size:10px\"><em>Hvis du har takket ja&nbsp;til at modtage e-mails&nbsp;med tilbud om kommende konferencer, men ikke l&aelig;ngere &oslash;nsker at modtage disse, bedes du&nbsp;skrive&nbsp;til&nbsp;forefrontkonf@trustworks.dk, s&aring; skal vi nok afmelde dig.&nbsp;Har du sp&oslash;rgsm&aring;l, som ikke er besvaret i vores <a href=\"http://forefront.trustworks.dk/faq/\">FAQ</a>, bedes du ligeledes kontakte os.</em></span></p>\n" +
                        "</div>");
        log.info("MailResource.sendingHTML");
        log.info("mail = " + mail);
        log.info("username = " + username);
        log.info("password = " + password);

        mailer.send(Mail.withHtml(
                        mail.getTo(),
                        mail.getSubject(),
                        mail.getBody())
                .addInlineAttachment("forefront-logo.png", photoService.findPhotoByRelatedUUID("c3395f9f-1d8d-476e-a517-c83e3b86545a").getFile(), "image/png", "<forefront@trustworks.dk>")
                .addInlineAttachment("trustworks-logo.png", photoService.findPhotoByRelatedUUID("91af1119-7725-4309-8ae8-131463d8d23c").getFile(), "image/png", "<trustworks@trustworks.dk>")
        );
        mail.setStatus(MailStatus.SENT);
        mail.persist();
    }
}