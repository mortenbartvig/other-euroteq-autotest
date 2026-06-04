package dk.dtu.ait.euroteq.autotest.controller;

import dk.dtu.ait.euroteq.autotest.dto.MatrixResponse;
import dk.dtu.ait.euroteq.autotest.dto.TestResultDto;
import dk.dtu.ait.euroteq.autotest.dto.TestRunDto;
import dk.dtu.ait.euroteq.autotest.entity.*;
import dk.dtu.ait.euroteq.autotest.repository.OfferingRepository;
import dk.dtu.ait.euroteq.autotest.repository.TestUserRepository;
import dk.dtu.ait.euroteq.autotest.repository.*;
import dk.dtu.ait.euroteq.autotest.service.TestExecutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/test-runs")
@Slf4j
public class TestRunController {

    private static final int MAX_STORED_RUNS = 10;

    private final TestRunRepository testRunRepository;
    private final TestResultRepository testResultRepository;
    private final HomeServerRepository homeServerRepository;
    private final HostServerRepository hostServerRepository;
    private final OfferingRepository offeringRepository;
    private final TestUserRepository testUserRepository;
    private final AppUserRepository appUserRepository;
    private final TestExecutionService testExecutionService;
    private final RestTemplate connectivityRestTemplate;

    @Value("${euroteq.mock-oauth-url}")
    private String mockOauthUrl;

    public TestRunController(
            TestRunRepository testRunRepository,
            TestResultRepository testResultRepository,
            HomeServerRepository homeServerRepository,
            HostServerRepository hostServerRepository,
            OfferingRepository offeringRepository,
            TestUserRepository testUserRepository,
            AppUserRepository appUserRepository,
            TestExecutionService testExecutionService,
            @Qualifier("connectivityRestTemplate") RestTemplate connectivityRestTemplate) {
        this.testRunRepository = testRunRepository;
        this.testResultRepository = testResultRepository;
        this.homeServerRepository = homeServerRepository;
        this.hostServerRepository = hostServerRepository;
        this.offeringRepository = offeringRepository;
        this.testUserRepository = testUserRepository;
        this.appUserRepository = appUserRepository;
        this.testExecutionService = testExecutionService;
        this.connectivityRestTemplate = connectivityRestTemplate;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TestRunDto> startTestRun() {
        // Prune old runs — keep only the last (MAX_STORED_RUNS - 1) so the new one makes MAX_STORED_RUNS
        List<TestRun> allRuns = testRunRepository.findAllByOrderByStartedAtDesc();
        if (allRuns.size() >= MAX_STORED_RUNS) {
            List<TestRun> toDelete = allRuns.subList(MAX_STORED_RUNS - 1, allRuns.size());
            testRunRepository.deleteAll(toDelete);
            log.info("Pruned {} old test run(s)", toDelete.size());
        }

        AppUser currentUser = getCurrentUser();
        TestRun testRun = new TestRun();
        testRun.setStartedAt(Instant.now());
        testRun.setStartedBy(currentUser);
        testRun.setStatus(TestRun.Status.PENDING);
        TestRun saved = testRunRepository.save(testRun);

        log.info("Test run {} created by '{}'", saved.getId(), currentUser.getUsername());
        testExecutionService.runTests(saved);
        return ResponseEntity.status(201).body(TestRunDto.from(saved));
    }

    @GetMapping
    public ResponseEntity<List<TestRunDto>> listTestRuns() {
        return ResponseEntity.ok(testRunRepository.findAllByOrderByStartedAtDesc().stream()
                .map(TestRunDto::from).collect(Collectors.toList()));
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
        return testRunRepository.findById(id).map(testRun -> {
            List<TestResult> results = testResultRepository.findByTestRunIdWithDetails(id);

            // Group results by homeId:hostId
            Map<String, List<TestResult>> byCell = new LinkedHashMap<>();
            for (TestResult r : results) {
                Long homeId = r.getTestUser().getHomeServer().getId();
                Long hostId = r.getOffering().getHostServer().getId();
                byCell.computeIfAbsent(homeId + ":" + hostId, k -> new ArrayList<>()).add(r);
            }

            Map<Long, String> homeServerMap = new LinkedHashMap<>();
            Map<Long, String> hostServerMap = new LinkedHashMap<>();
            for (TestResult r : results) {
                HomeServer hs = r.getTestUser().getHomeServer();
                homeServerMap.put(hs.getId(), hs.getName());
                HostServer host = r.getOffering().getHostServer();
                hostServerMap.put(host.getId(), host.getName());
            }

            List<MatrixResponse.MatrixCell> cells = new ArrayList<>();
            for (Map.Entry<String, List<TestResult>> entry : byCell.entrySet()) {
                String[] parts = entry.getKey().split(":");
                List<TestResult> cellResults = entry.getValue();

                MatrixResponse.MatrixCell cell = new MatrixResponse.MatrixCell();
                cell.setHomeServerId(Long.parseLong(parts[0]));
                cell.setHostServerId(Long.parseLong(parts[1]));
                cell.setTotalTests(cellResults.size());

                long totalMs = 0; int countMs = 0;
                for (TestResult r : cellResults) {
                    switch (r.getActualResult()) {
                        case SUCCESS -> cell.setSuccessCount(cell.getSuccessCount() + 1);
                        case DENIED  -> cell.setDeniedCount(cell.getDeniedCount() + 1);
                        case ERROR   -> cell.setErrorCount(cell.getErrorCount() + 1);
                        case SKIPPED -> cell.setSkippedCount(cell.getSkippedCount() + 1);
                    }
                    if (r.isHasWarnings()) cell.setWarningCount(cell.getWarningCount() + 1);
                    if (r.getStartedAt() != null && r.getCompletedAt() != null) {
                        totalMs += Duration.between(r.getStartedAt(), r.getCompletedAt()).toMillis();
                        countMs++;
                    }
                }
                if (countMs > 0) cell.setAvgDurationMs(totalMs / countMs);
                if (cell.getTotalTests() > 0) cell.setSuccessRate((double) cell.getSuccessCount() / cell.getTotalTests());
                cell.setStatus(computeCellStatus(cell));
                cells.add(cell);
            }

            MatrixResponse response = new MatrixResponse();
            response.setHomeServers(homeServerMap.entrySet().stream()
                    .map(e -> new MatrixResponse.ServerRef(e.getKey(), e.getValue())).collect(Collectors.toList()));
            response.setHostServers(hostServerMap.entrySet().stream()
                    .map(e -> new MatrixResponse.ServerRef(e.getKey(), e.getValue())).collect(Collectors.toList()));
            response.setCells(cells);
            return ResponseEntity.ok(response);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/detail")
    public ResponseEntity<List<TestResultDto>> getDetail(@PathVariable Long id,
                                                          @RequestParam(required = false) Long homeServerId,
                                                          @RequestParam(required = false) Long hostServerId) {
        if (!testRunRepository.existsById(id)) return ResponseEntity.notFound().build();
        List<TestResult> results = (homeServerId != null && hostServerId != null)
                ? testResultRepository.findByTestRunIdAndServers(id, homeServerId, hostServerId)
                : testResultRepository.findByTestRunIdWithDetails(id);
        return ResponseEntity.ok(results.stream().map(TestResultDto::from).collect(Collectors.toList()));
    }

    @GetMapping("/config-status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> configStatus() {
        int homeServers = (int) homeServerRepository.count();
        int testUsers   = (int) testUserRepository.count();
        int hostServers = (int) hostServerRepository.count();
        int offerings   = (int) offeringRepository.count();

        List<String> issues = new ArrayList<>();
        if (homeServers == 0) issues.add("No home servers configured");
        if (testUsers == 0)   issues.add("No test users configured (add users inside a home server)");
        if (hostServers == 0) issues.add("No host servers configured");
        if (offerings == 0)   issues.add("No offerings configured (add offerings inside a host server)");

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("homeServerCount",  homeServers);
        status.put("testUserCount",    testUsers);
        status.put("hostServerCount",  hostServers);
        status.put("offeringCount",    offerings);
        status.put("totalTestCases",   testUsers * offerings);
        status.put("ready",   issues.isEmpty());
        status.put("issues",  issues);
        return ResponseEntity.ok(status);
    }

    @GetMapping("/connectivity")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> connectivity() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (HomeServer hs : homeServerRepository.findAll()) {
            results.add(ping(hs.getName(), hs.getUrl(), "HOME_SERVER"));
        }
        for (HostServer hs : hostServerRepository.findAll()) {
            results.add(ping(hs.getName(), hs.getUrl(), "HOST_SERVER"));
        }
        results.add(ping("Mock OAuth", mockOauthUrl, "MOCK_OAUTH"));
        return ResponseEntity.ok(results);
    }

    @GetMapping("/history-matrix")
    public ResponseEntity<Map<String, Object>> historyMatrix() {
        // Get last 10 completed/failed runs ordered newest first
        List<TestRun> recentRuns = testRunRepository.findAllByOrderByStartedAtDesc().stream()
                .filter(r -> r.getStatus() == TestRun.Status.COMPLETED || r.getStatus() == TestRun.Status.FAILED)
                .limit(10)
                .collect(Collectors.toList());

        if (recentRuns.isEmpty()) return ResponseEntity.ok(Map.of("cells", Map.of()));

        List<Long> runIds = recentRuns.stream().map(TestRun::getId).collect(Collectors.toList());
        List<TestResult> allResults = testResultRepository.findByTestRunIdsWithDetails(runIds);

        // Group by runId then by homeId:hostId
        Map<Long, Map<String, int[]>> byRun = new LinkedHashMap<>();
        for (TestResult r : allResults) {
            Long homeId = r.getTestUser().getHomeServer().getId();
            Long hostId = r.getOffering().getHostServer().getId();
            String key = homeId + ":" + hostId;
            byRun.computeIfAbsent(r.getTestRun().getId(), k -> new LinkedHashMap<>())
                 .computeIfAbsent(key, k -> new int[]{0, 0}); // [total, success]
            byRun.get(r.getTestRun().getId()).get(key)[0]++;
            if (r.getActualResult() == TestResult.ActualResult.SUCCESS)
                byRun.get(r.getTestRun().getId()).get(key)[1]++;
        }

        // Build cell -> list of history entries (newest first)
        Map<String, List<Map<String, Object>>> cellHistory = new LinkedHashMap<>();
        for (TestRun run : recentRuns) {
            Map<String, int[]> cells = byRun.getOrDefault(run.getId(), Map.of());
            for (Map.Entry<String, int[]> e : cells.entrySet()) {
                int total = e.getValue()[0], success = e.getValue()[1];
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("runId", run.getId());
                h.put("total", total);
                h.put("success", success);
                h.put("status", success == total ? "success" : success > 0 ? "partial" : "failed");
                cellHistory.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(h);
            }
        }

        return ResponseEntity.ok(Map.of("cells", cellHistory));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> ping(String name, String url, String type) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("url",  url);
        r.put("type", type);
        long start = System.currentTimeMillis();
        try {
            connectivityRestTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(new HttpHeaders()), String.class);
            r.put("reachable", true);
        } catch (HttpStatusCodeException e) {
            r.put("reachable", true); // got an HTTP response = reachable
            r.put("httpStatus", e.getStatusCode().value());
        } catch (Exception e) {
            r.put("reachable", false);
            r.put("error", e.getMessage());
        }
        r.put("durationMs", System.currentTimeMillis() - start);
        return r;
    }

    private String computeCellStatus(MatrixResponse.MatrixCell cell) {
        int total = cell.getTotalTests();
        if (total == 0) return "pending";
        int nonSkipped = total - cell.getSkippedCount();
        if (nonSkipped == 0) return "pending";
        if (cell.getErrorCount() > 0 && cell.getSuccessCount() == 0 && cell.getDeniedCount() == 0) return "error";
        if (cell.getSuccessCount() == nonSkipped) return "success";
        if (cell.getSuccessCount() == 0) return "failed";
        return "partial";
    }

    private AppUser getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return appUserRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new RuntimeException("Current user not found"));
    }
}
