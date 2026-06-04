package dk.dtu.ait.euroteq.autotest.dto;

import dk.dtu.ait.euroteq.autotest.entity.AppUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CreateUserRequest {

    @NotBlank
    private String username;

    @NotBlank
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @NotNull
    private AppUser.Role role;
}
