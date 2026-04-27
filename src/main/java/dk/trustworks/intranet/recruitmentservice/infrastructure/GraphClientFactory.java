package dk.trustworks.intranet.recruitmentservice.infrastructure;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * CDI producer for the Microsoft Graph SDK client used by recruitment integrations.
 *
 * <p>Uses app-only auth (client credentials) with the {@code .default} scope so the
 * registered Azure AD app permissions decide what the token can do. The TAM mailbox
 * is referenced explicitly per call (e.g. {@code client.users().byUserId(mailbox)})
 * — this factory never embeds a user id.
 *
 * <p>Resolution: always produced. If config values are empty (e.g. local dev without
 * Azure secrets), credential acquisition fails lazily on first Graph call. The
 * NoopOutlookCalendarPort still wins via {@code @Priority(1)} when the live impl
 * is not registered, so this factory is only consumed by {@code OutlookCalendarPortImpl}.
 */
@ApplicationScoped
public class GraphClientFactory {

    @ConfigProperty(name = "recruitment.graph.tenant-id")
    String tenantId;

    @ConfigProperty(name = "recruitment.graph.client-id")
    String clientId;

    @ConfigProperty(name = "recruitment.graph.client-secret")
    String clientSecret;

    @Produces
    @ApplicationScoped
    public GraphServiceClient produceClient() {
        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .tenantId(tenantId)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build();
        return new GraphServiceClient(credential, "https://graph.microsoft.com/.default");
    }
}
