package dk.dtu.ait.euroteq.autotest.dto;

import dk.dtu.ait.euroteq.autotest.entity.SimulationInstitution;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class SimulationInstitutionDto {

    private Long id;
    private String name;
    private Integer usersOverride;
    private Integer offeringsOverride;
 private Integer passRateOverride;
    private Boolean useGlobalPassRate;
    private boolean homeServerOffline;
    private boolean hostServerOffline;

 public static SimulationInstitutionDto from(SimulationInstitution inst) {
        SimulationInstitutionDto dto = new SimulationInstitutionDto();
        dto.setId(inst.getId());
        dto.setName(inst.getName());
        dto.setUsersOverride(inst.getUsersOverride());
        dto.setOfferingsOverride(inst.getOfferingsOverride());
        dto.setPassRateOverride(inst.getPassRateOverride());
        dto.setUseGlobalPassRate(inst.getUseGlobalPassRate() != null ? inst.getUseGlobalPassRate() : (inst.getPassRateOverride() == null));
        dto.setHomeServerOffline(inst.isHomeServerOffline());
        dto.setHostServerOffline(inst.isHostServerOffline());
        return dto;
    }
}
