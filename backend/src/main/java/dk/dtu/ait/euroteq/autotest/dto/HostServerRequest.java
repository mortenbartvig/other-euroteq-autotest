package dk.dtu.ait.euroteq.autotest.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class HostServerRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String url;

    private String enrollmentPath = "/persons/{personId}/associations";

    private String enrollmentMode = "DIRECT";

    private String brokerScope = "offline_access";

    private Long ownerId;

    private String basicAuthUsername;
    private String basicAuthPassword;
}
