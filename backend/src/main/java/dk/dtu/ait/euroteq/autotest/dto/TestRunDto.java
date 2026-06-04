package dk.dtu.ait.euroteq.autotest.dto;

import dk.dtu.ait.euroteq.autotest.entity.TestRun;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
public class TestRunDto {

    private Long id;
    private Instant startedAt;
    private Instant completedAt;
    private String startedBy;
    private TestRun.Status status;
    private int totalResults;
    private String statusMessage;

    public static TestRunDto from(TestRun run) {
        TestRunDto dto = new TestRunDto();
        dto.setId(run.getId());
        dto.setStartedAt(run.getStartedAt());
        dto.setCompletedAt(run.getCompletedAt());
        dto.setStartedBy(run.getStartedBy().getUsername());
        dto.setStatus(run.getStatus());
        dto.setTotalResults(run.getResults().size());
        dto.setStatusMessage(run.getStatusMessage());
        return dto;
    }
}
