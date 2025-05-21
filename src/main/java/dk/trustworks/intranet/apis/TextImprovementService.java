package dk.trustworks.intranet.apis;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;

@ApplicationScoped
@JBossLog
public class TextImprovementService {

    @ConfigProperty(name = "edenai.api.key")
    String edenaiApiKey;

    public String improveText(String text, String fileName) throws IOException {

        return "";
    }
}