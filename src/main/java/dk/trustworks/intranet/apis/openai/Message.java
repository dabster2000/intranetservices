package dk.trustworks.intranet.apis.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class Message {
    // Getters and setters
    @JsonProperty("role")
    private String role;

    @JsonProperty("content")
    private String content;

    // Default constructor is required for Jackson
    public Message() {}

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

}
