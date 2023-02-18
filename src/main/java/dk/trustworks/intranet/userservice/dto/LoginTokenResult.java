package dk.trustworks.intranet.userservice.dto;

import dk.trustworks.intranet.userservice.model.Role;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginTokenResult {

    private String token;
    private String useruuid;
    private boolean success;
    private String failureReason;
    private List<Role> roles;

}
