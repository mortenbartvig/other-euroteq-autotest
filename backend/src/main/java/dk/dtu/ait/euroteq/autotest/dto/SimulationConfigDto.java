package dk.dtu.ait.euroteq.autotest.dto;

import dk.dtu.ait.euroteq.autotest.entity.SimulationConfig;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class SimulationConfigDto {

    private Long id;
    private Integer globalUsersPerInst;
    private Integer globalOfferingsPerInst;
    private Integer globalPassRate;
    private Boolean slowAsPercent;
    private Double slowPercent;
    private Integer slowCount;
    private Double normalDurationMin;
    private Double normalDurationMax;
    private Double slowDurationMin;
    private Double slowDurationMax;
    private List<SimulationInstitutionDto> institutions;

    public static SimulationConfigDto from(SimulationConfig config) {
        SimulationConfigDto dto = new SimulationConfigDto();
        dto.setId(config.getId());
        dto.setGlobalUsersPerInst(config.getGlobalUsersPerInst());
        dto.setGlobalOfferingsPerInst(config.getGlobalOfferingsPerInst());
  dto.setGlobalPassRate(config.getGlobalPassRate());
        dto.setSlowAsPercent(config.getSlowAsPercent());
        dto.setSlowPercent(config.getSlowPercent());
        dto.setSlowCount(config.getSlowCount());
        dto.setNormalDurationMin(config.getNormalDurationMin());
        dto.setNormalDurationMax(config.getNormalDurationMax());
        dto.setSlowDurationMin(config.getSlowDurationMin());
        dto.setSlowDurationMax(config.getSlowDurationMax());
        dto.setInstitutions(config.getInstitutions().stream()
                .map(SimulationInstitutionDto::from)
                .collect(java.util.stream.Collectors.toList()));
        return dto;
    }
}
