package dk.trustworks.intranet.apigateway.forms;

import lombok.extern.jbosslog.JBossLog;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.HashMap;
import java.util.Map;

@JBossLog
@Path("/form")
public class FormDataResource {

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public void receiveForm(String formData) {
        Map<String, String> formFields = parseFormData(formData);
        String name = formFields.get("name");
        String email = formFields.get("email");
        // You can now use the name and email variables to process the form data as needed
    }

    private Map<String, String> parseFormData(String formData) {
        // Split the form data into individual fields
        String[] fields = formData.split("&");
        Map<String, String> formFields = new HashMap<>();
        for (String field : fields) {
            // Split each field into a name-value pair
            String[] nameValue = field.split("=");
            String name = nameValue[0];
            String value = nameValue[1];
            // Add the name-value pair to the map
            formFields.put(name, value);
        }
        return formFields;
    }
}

/*
form_id=1050&time=2022-12-29+11%3A16%3A20&source_url=http%3A%2F%2Fforefront.34.241.72.253.nip.io%2Ftest-page%2F%3Fpreview_id%3D1352%26preview_nonce%3Dcd0bee0f6f%26_thumbnail_id%3D-1%26preview%3Dtrue&post_id=1352&user_id=1&user_agent=Mozilla%2F5.0+%28Macintosh%3B+Intel+Mac+OS+X+10_15_7%29+AppleWebKit%2F605.1.15+%28KHTML%2C+like+Gecko%29+Version%2F16.1+Safari%2F605.1.15&ip=87.52.110.9&is_read=0&privacy_scrub_date=2022-12-29&on_privacy_scrub=anonymize&name=ha&e-mail=hans%40godfather.dk and email:

form_id=1050&time=2022-12-29+11:16:20&
source_url=http://forefront.34.241.72.253.nip.io/test-page/?preview_id=1352&
preview_nonce=cd0bee0f6f&_thumbnail_id=-1&preview=true&
post_id=1352&user_id=1&user_agent=Mozilla/5.0+(Macintosh;+Intel+Mac+OS+X+10_15_7)+AppleWebKit/605.1.15+(KHTML,+like+Gecko)+Version/16.1+Safari/605.1.15&ip=87.52.110.9&is_read=0&
privacy_scrub_date=2022-12-29&
on_privacy_scrub=anonymize&
name=ha&
e-mail=hans@godfather.dk
 and
email:

 */