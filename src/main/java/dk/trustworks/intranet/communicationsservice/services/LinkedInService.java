package dk.trustworks.intranet.communicationsservice.services;

import com.echobox.api.linkedin.client.DefaultVersionedLinkedInClient;
import com.echobox.api.linkedin.client.LinkedInClient;
import com.echobox.api.linkedin.client.VersionedLinkedInClient;
import com.echobox.api.linkedin.version.Version;

import java.io.IOException;


public class LinkedInService {

    private static final String CLIENT_SECRET = "AQXCpJhxUPyyJIiFtxUT9LMPf2hHK-wM6fu3SUXJlqBLUgl1aYIBNNju-1pbqQk2LpliOm43CzOp74gBG7g13JPXoHIeBnXtdGLiXainY1yKceq2mLLPgOe3hqlG-8hL0DIU-wz79Uuy_4QVEcbvqbUPOOqe5XIlFTSfsu9WUTdY8ZQAJ8WutVtCnDaskHsUiHnOgy-90PpnkCTzxCYjc6Kpg-gWL_NQftxwj9j9CXv-hKS7qNyDRwZSY7V9Za0zbultXVG6odubugjQ-MD7ldE9egbS-CKNBaTb4_xYKaK4bamKJs0YShPH_xYJtzJAmtncLupK0vI1kRp3IOSYZia_AcP9OA";
    private static final String COMPANY_ID = "7894phaabb6j04";

    public static void main(String[] args) {
        try {
            String latestPost = fetchLatestCompanyPost(COMPANY_ID);
            System.out.println("Latest Post: " + latestPost);
        } catch (IOException e) {
            System.err.println("Error fetching latest post: " + e.getMessage());
        }
    }

    //https://www.linkedin.com/company/5082862
    public static String fetchLatestCompanyPost(String companyId) throws IOException {
        VersionedLinkedInClient client = new DefaultVersionedLinkedInClient(Version.DEFAULT_VERSION);
        LinkedInClient.AccessToken accessToken = client.obtainUserAccessToken("7894phaabb6j04", "P55QWU7NBaTv3Jt4", "https://www.linkedin.com/developers/tools/oauth/redirect", CLIENT_SECRET);

        /*
        URL url = new URL("https://api.linkedin.com/v2/shares?q=owners&owners=urn:li:organization:" + companyId + "&count=1&sortBy=CREATED&order=DESC&oauth2_access_token=" + CLIENT_SECRET);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");

        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("Failed to fetch latest post: HTTP error code: " + connection.getResponseCode());
        }

        JsonReader reader = Json.createReader(connection.getInputStream());
        JsonObject jsonResponse = reader.readObject();
        JsonArray elements = jsonResponse.getJsonArray("elements");

        if (elements.size() == 0) {
            return "No posts found.";
        }

        JsonObject latestPostObject = elements.getJsonObject(0);
        JsonObject postContent = latestPostObject.getJsonObject("text");
        String latestPostText = postContent.getString("text");

        reader.close();
        connection.disconnect();

         */

        return "Done";
    }

}
