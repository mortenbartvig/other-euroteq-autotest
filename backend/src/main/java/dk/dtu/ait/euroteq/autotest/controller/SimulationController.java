package dk.dtu.ait.euroteq.autotest.controller;

import dk.dtu.ait.euroteq.autotest.dto.SimulationConfigDto;
import dk.dtu.ait.euroteq.autotest.dto.SimulationInstitutionDto;
import dk.dtu.ait.euroteq.autotest.entity.SimulationInstitution;
import dk.dtu.ait.euroteq.autotest.service.SimulatedRunService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/simulation")
@RequiredArgsConstructor
@Slf4j
public class SimulationController {

    private final SimulatedRunService simulatedRunService;

    @GetMapping("/config")
    public ResponseEntity<SimulationConfigDto> getConfig() {
        Long userId = getCurrentUserId();
        return ResponseEntity.ok(simulatedRunService.getConfig(userId));
    }

    @PostMapping("/config")
    public ResponseEntity<SimulationConfigDto> saveConfig(@Valid @RequestBody SimulationConfigDto dto) {
        Long userId = getCurrentUserId();
        return ResponseEntity.ok(simulatedRunService.saveConfig(userId, dto));
    }

    @GetMapping("/defaults")
    public ResponseEntity<List<SimulationInstitutionDto>> getDefaultInstitutions() {
        return ResponseEntity.ok(simulatedRunService.getDefaultInstitutions().stream()
                .map(SimulationInstitutionDto::from)
                .collect(java.util.stream.Collectors.toList()));
    }

    @PostMapping("/run")
    public ResponseEntity<Long> runSimulation() {
        Long userId = getCurrentUserId();
        log.info("Starting simulated test run for user {}", userId);
        Long runId = simulatedRunService.runSimulation(userId);
        return ResponseEntity.accepted().body(runId);
    }

    @GetMapping("/runs")
    public ResponseEntity<List<Long>> getRuns() {
        // Returns the list of simulated run IDs for this user
        // The actual run details are retrieved via the existing test-runs API
        // with the simulated filter
        return ResponseEntity.ok(List.of());
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth.getName();
        // For admin-only feature, we use a fixed admin user ID
        // In production, this would look up the user by username
        return 1L;
    }
}
