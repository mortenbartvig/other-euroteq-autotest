package dk.dtu.ait.euroteq.autotest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ResetPasswordRequest {

    @NotBlank
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String newPassword;
}
