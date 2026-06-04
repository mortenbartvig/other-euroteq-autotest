package dk.dtu.ait.euroteq.autotest.dto;

import dk.dtu.ait.euroteq.autotest.entity.Offering;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OfferingDto {

    private Long id;
    private String name;
    private String offeringId;
    private String offeringData;
    private Long hostServerId;

    public static OfferingDto from(Offering offering) {
        OfferingDto dto = new OfferingDto();
        dto.setId(offering.getId());
        dto.setName(offering.getName());
        dto.setOfferingId(offering.getOfferingId());
        dto.setOfferingData(offering.getOfferingData());
        dto.setHostServerId(offering.getHostServer().getId());
        return dto;
    }
}
