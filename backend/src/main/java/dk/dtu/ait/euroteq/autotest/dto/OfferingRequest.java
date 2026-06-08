package dk.dtu.ait.euroteq.autotest.dto;

import dk.dtu.ait.euroteq.autotest.entity.AcademicLevel;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OfferingRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String offeringId;

    private String offeringData;

    private AcademicLevel courseLevel;
}
