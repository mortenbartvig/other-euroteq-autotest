package dk.dtu.ait.euroteq.autotest.dto;

import dk.dtu.ait.euroteq.autotest.entity.TestResult;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
public class TestResultDto {

    private Long id;
    private Long testUserId;
    private String testUserName;
    private Long offeringId;
    private String offeringName;
    private Long homeServerId;
    private String homeServerName;
    private Long hostServerId;
    private String hostServerName;
    private TestResult.ActualResult actualResult;
    private String errorMessage;
    private String stepDetails;
   private Instant startedAt;
    private Instant completedAt;
    private boolean hasWarnings;
    private boolean isSlow;
    private boolean isVerySlow;

    public static TestResultDto from(TestResult result) {
        TestResultDto dto = new TestResultDto();
        dto.setId(result.getId());
        dto.setTestUserId(result.getTestUser().getId());
        dto.setTestUserName(result.getTestUser().getName());
        dto.setOfferingId(result.getOffering().getId());
        dto.setOfferingName(result.getOffering().getName());
        dto.setHomeServerId(result.getTestUser().getHomeServer() != null ? result.getTestUser().getHomeServer().getId() : null);
        dto.setHomeServerName(result.getTestUser().getHomeServer() != null ? result.getTestUser().getHomeServer().getName() : null);
        dto.setHostServerId(result.getOffering().getHostServer() != null ? result.getOffering().getHostServer().getId() : null);
        dto.setHostServerName(result.getOffering().getHostServer() != null ? result.getOffering().getHostServer().getName() : null);
        dto.setActualResult(result.getActualResult());
        dto.setErrorMessage(result.getErrorMessage());
        dto.setStepDetails(result.getStepDetails());
        dto.setStartedAt(result.getStartedAt());
        dto.setCompletedAt(result.getCompletedAt());
        dto.setHasWarnings(result.isHasWarnings());
     dto.setSlow(result.isSlow());
        dto.setVerySlow(result.isVerySlow());
        return dto;
    }
}
