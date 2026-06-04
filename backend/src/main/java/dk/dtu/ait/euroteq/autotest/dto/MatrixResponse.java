package dk.dtu.ait.euroteq.autotest.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class MatrixResponse {

    private List<ServerRef> homeServers;
    private List<ServerRef> hostServers;
    private List<MatrixCell> cells;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class ServerRef {
        private Long id;
        private String name;

        public ServerRef(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class MatrixCell {
        private Long homeServerId;
        private Long hostServerId;
        private int totalTests;
        private int successCount;
        private int deniedCount;
        private int errorCount;
        private int skippedCount;
        private double successRate;
        private String status;
        private Long avgDurationMs;
        private int warningCount;
    }
}
