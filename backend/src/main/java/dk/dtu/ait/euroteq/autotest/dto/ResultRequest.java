package dk.dtu.ait.euroteq.autotest.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ResultRequest {

    @NotBlank
    private String name;

    private String state;
    private String pass;
    private String comment;
    private String score;
    private String resultDate;
    private String ext;
    private String studyLoad;
}
