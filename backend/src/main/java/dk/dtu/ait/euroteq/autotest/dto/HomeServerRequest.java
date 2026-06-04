package dk.dtu.ait.euroteq.autotest.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class HomeServerRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String url;

    private Long ownerId;

    private String basicAuthUsername;
    private String basicAuthPassword;
}
