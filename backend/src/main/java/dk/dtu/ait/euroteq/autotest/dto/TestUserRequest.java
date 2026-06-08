package dk.dtu.ait.euroteq.autotest.dto;

import dk.dtu.ait.euroteq.autotest.entity.AcademicLevel;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class TestUserRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String username;

    private String claims;

    private AcademicLevel academicLevel;

    private boolean alwaysDenied = false;
}
