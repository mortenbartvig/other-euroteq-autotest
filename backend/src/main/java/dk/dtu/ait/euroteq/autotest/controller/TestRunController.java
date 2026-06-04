package dk.dtu.ait.euroteq.autotest.controller;

import dk.dtu.ait.euroteq.autotest.dto.MatrixResponse;
import dk.dtu.ait.euroteq.autotest.dto.TestResultDto;
import dk.dtu.ait.euroteq.autotest.dto.TestRunDto;
import dk.dtu.ait.euroteq.autotest.entity.*;
import dk.dtu.ait.euroteq.autotest.repository.OfferingRepository;
import dk.dtu.ait.euroteq.autotest.repository.TestUserRepository;
import dk.dtu.ait.euroteq.autotest.repository.*;
import dk.dtu.ait.euroteq.autotest.service.TestExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/test-runs")
@RequiredArgsConstructor
@Slf4j
public class TestRunController {

    private final TestRunRepository testRunRepository;
    private final TestResultRepository testResultRepository;
    private final HomeServerRepository homeServerRepository;
    private final HostServerRepository hostServerRepository;
    private final OfferingRepository offeringRepository;
    private final TestUserRepository testUserRepository;
    private final AppUserRepository appUserRepository;
    private final TestExecutionService testExecutionService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TestRunDto> startTestRun() {
        AppUser currentUser = getCurrentUser();

        TestRun testRun = new TestRun();
        testRun.setStartedAt(Instant.now());
        testRun.setStartedBy(currentUser);
        testRun.setStatus(TestRun.Status.PENDING);
        TestRun saved = testRunRepository.save(testRun);

        log.info("Test run {} created by '{}'", saved.getId(), currentUser.getUsername());

        // Launch async execution
        testExecutionService.runTests(saved);

        return ResponseEntity.status(201).body(TestRunDto.from(saved));
    }

    @GetMapping
    public ResponseEntity<List<TestRunDto>> listTestRuns() {
        List<TestRunDto> runs = testRunRepository.findAllByOrderByStartedAtDesc().stream()
                .map(TestRunDto::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(runs);
    }

    @GetMapping("/latest")
    public ResponseEntity<TestRunDto> getLatestTestRun() {
        return testRunRepository.findTopByOrderByStartedAtDesc()
                .map(run -> ResponseEntity.ok(TestRunDto.from(run)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TestRunDto> getTestRun(@PathVariable Long id) {
        return testRunRepository.findById(id)
                .map(run -> ResponseEntity.ok(TestRunDto.from(run)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/matrix")
    public ResponseEntity<MatrixResponse> getMatrix(@PathVariable Long id) {
        return testRunRepository.findById(id)
                .map(testRun -> {
                    List<TestResult> results = testResultRepository.findByTestRunIdWithDetails(id);

                    // Collect distinct home servers and host servers from results
                    Map<Long, String> homeServerMap = new LinkedHashMap<>();
                    Map<Long, String> hostServerMap = new LinkedHashMap<>();

                    for (TestResult result : results) {
                        HomeServer hs = result.getTestUser().getHomeServer();
                        homeServerMap.put(hs.getId(), hs.getName());
                        HostServer host = result.getOffering().getHostServer();
                        hostServerMap.put(host.getId(), host.getName());
                    }

                    // Build cells: one per homeServer x hostServer combination
                    Map<String, MatrixResponse.MatrixCell> cellMap = new LinkedHashMap<>();

                    for (TestResult result : results) {
                        Long homeId = result.getTestUser().getHomeServer().getId();
                        Long hostId = result.getOffering().getHostServer().getId();
                        String key = homeId + ":" + hostId;

                        MatrixResponse.MatrixCell cell = cellMap.computeIfAbsent(key, k -> {
                            MatrixResponse.MatrixCell c = new MatrixResponse.MatrixCell();
                            c.setHomeServerId(homeId);
                            c.setHostServerId(hostId);
                            return c;
                        });

                        cell.setTotalTests(cell.getTotalTests() + 1);

                        switch (result.getActualResult()) {
                            case SUCCESS -> cell.setSuccessCount(cell.getSuccessCount() + 1);
                            case DENIED -> cell.setDeniedCount(cell.getDeniedCount() + 1);
                            case ERROR -> cell.setErrorCount(cell.getErrorCount() + 1);
                            case SKIPPED -> cell.setSkippedCount(cell.getSkippedCount() + 1);
                        }
                    }

                    // Compute success rates and status
                    for (MatrixResponse.MatrixCell cell : cellMap.values()) {
                        int total = cell.getTotalTests();
                        if (total > 0) {
                            cell.setSuccessRate((double) cell.getSuccessCount() / total);
                        }
                        cell.setStatus(computeCellStatus(cell));
                    }

                    MatrixResponse response = new MatrixResponse();
                    response.setHomeServers(homeServerMap.entrySet().stream()
                            .map(e -> new MatrixResponse.ServerRef(e.getKey(), e.getValue()))
                            .collect(Collectors.toList()));
                    response.setHostServers(hostServerMap.entrySet().stream()
                            .map(e -> new MatrixResponse.ServerRef(e.getKey(), e.getValue()))
                            .collect(Collectors.toList()));
                    response.setCells(new ArrayList<>(cellMap.values()));

                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/detail")
    public ResponseEntity<List<TestResultDto>> getDetail(@PathVariable Long id,
                                                          @RequestParam(required = false) Long homeServerId,
                                                          @RequestParam(required = false) Long hostServerId) {
        if (!testRunRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        List<TestResult> results;
        if (homeServerId != null && hostServerId != null) {
            results = testResultRepository.findByTestRunIdAndServers(id, homeServerId, hostServerId);
        } else {
            results = testResultRepository.findByTestRunIdWithDetails(id);
        }

        List<TestResultDto> dtos = results.stream()
                .map(TestResultDto::from)
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/config-status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> configStatus() {
        int homeServers = (int) homeServerRepository.count();
        int testUsers = (int) testUserRepository.count();
        int hostServers = (int) hostServerRepository.count();
        int offerings = (int) offeringRepository.count();
        int totalTestCases = testUsers * offerings;

        List<String> issues = new ArrayList<>();
        if (homeServers == 0) issues.add("No home servers configured");
        if (testUsers == 0) issues.add("No test users configured (add users inside a home server)");
        if (hostServers == 0) issues.add("No host servers configured");
        if (offerings == 0) issues.add("No offerings configured (add offerings inside a host server)");

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("homeServerCount", homeServers);
        status.put("testUserCount", testUsers);
        status.put("hostServerCount", hostServers);
        status.put("offeringCount", offerings);
        status.put("totalTestCases", totalTestCases);
        status.put("ready", issues.isEmpty());
        status.put("issues", issues);
        return ResponseEntity.ok(status);
    }

    private String computeCellStatus(MatrixResponse.MatrixCell cell) {
        int total = cell.getTotalTests();
        if (total == 0) return "pending";

        int nonSkipped = total - cell.getSkippedCount();
        if (nonSkipped == 0) return "pending";

        if (cell.getErrorCount() > 0 && cell.getSuccessCount() == 0 && cell.getDeniedCount() == 0) {
            return "error";
        }
        if (cell.getSuccessCount() == nonSkipped) {
            return "success";
        }
        if (cell.getSuccessCount() == 0) {
            return "failed";
        }
        return "partial";
    }

    private AppUser getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return appUserRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new RuntimeException("Current user not found"));
    }
}
