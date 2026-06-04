package dk.dtu.ait.euroteq.autotest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.dtu.ait.euroteq.autotest.entity.*;
import dk.dtu.ait.euroteq.autotest.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TestExecutionService {

    private final TestRunRepository testRunRepository;
    private final TestResultRepository testResultRepository;
    private final HomeServerRepository homeServerRepository;
    private final HostServerRepository hostServerRepository;
    private final OfferingRepository offeringRepository;
    private final TestUserRepository testUserRepository;
    private final TokenService tokenService;
    private final RestTemplate standardRestTemplate;
    private final RestTemplate noRedirectRestTemplate;
    private final ObjectMapper objectMapper;
    private final Executor testExecutor;
    private final AssociationCaptureStore captureStore;
    private final ResultRepository resultRepository;
    private final RealTokenStore realTokenStore;

    @Value("${euroteq.mock-oauth-url}")
    private String mockOauthUrl;

    @Value("${euroteq.base-url}")
    private String autotestBaseUrl;

    @Value("${euroteq.mock-user.suffix:}")
    private String mockUserSuffix;

    @Value("${euroteq.retry.max-attempts:2}")
    private int maxRetryAttempts;

    @Value("${euroteq.retry.delay-ms:1000}")
    private long retryDelayMs;

    public TestExecutionService(
            TestRunRepository testRunRepository,
            TestResultRepository testResultRepository,
            HomeServerRepository homeServerRepository,
            HostServerRepository hostServerRepository,
            OfferingRepository offeringRepository,
            TestUserRepository testUserRepository,
            TokenService tokenService,
            @Qualifier("standardRestTemplate") RestTemplate standardRestTemplate,
            @Qualifier("noRedirectRestTemplate") RestTemplate noRedirectRestTemplate,
            ObjectMapper objectMapper,
            @Qualifier("testExecutor") Executor testExecutor,
            AssociationCaptureStore captureStore,
            ResultRepository resultRepository,
            RealTokenStore realTokenStore) {
        this.testRunRepository = testRunRepository;
        this.testResultRepository = testResultRepository;
        this.homeServerRepository = homeServerRepository;
        this.hostServerRepository = hostServerRepository;
        this.offeringRepository = offeringRepository;
        this.testUserRepository = testUserRepository;
        this.tokenService = tokenService;
        this.standardRestTemplate = standardRestTemplate;
        this.noRedirectRestTemplate = noRedirectRestTemplate;
        this.objectMapper = objectMapper;
        this.testExecutor = testExecutor;
        this.captureStore = captureStore;
        this.resultRepository = resultRepository;
        this.realTokenStore = realTokenStore;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> newStep(String name, String phase) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", name);
        step.put("phase", phase);
        return step;
    }

    private long mark() { return System.currentTimeMillis(); }

    private void setDuration(Map<String, Object> step, long startMs) {
        step.put("durationMs", System.currentTimeMillis() - startMs);
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> { T get() throws Exception; }

    private <T> T withRetry(ThrowingSupplier<T> action) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < maxRetryAttempts; attempt++) {
            try {
                return action.get();
            } catch (ResourceAccessException e) {
                last = e;
                log.warn("Network error (attempt {}/{}): {}", attempt + 1, maxRetryAttempts, e.getMessage());
                if (attempt < maxRetryAttempts - 1) {
                    try { Thread.sleep(retryDelayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        throw Objects.requireNonNull(last);
    }

    // ── Main orchestration ────────────────────────────────────────────────────

    @Async("testExecutor")
    public CompletableFuture<Void> runTests(TestRun testRun) {
        log.info("Starting test run {}", testRun.getId());

        try {
            testRun.setStatus(TestRun.Status.RUNNING);
            testRunRepository.save(testRun);

            List<HomeServer> homeServers = homeServerRepository.findAll();
            List<HostServer> hostServers = hostServerRepository.findAll();
            List<Offering> allOfferings = offeringRepository.findAll();
            List<TestUser> allTestUsers = testUserRepository.findAll();

            List<String> issues = new ArrayList<>();
            if (homeServers.isEmpty()) issues.add("no home servers configured");
            if (allTestUsers.isEmpty()) issues.add("no test users configured (add users to home servers)");
            if (hostServers.isEmpty()) issues.add("no host servers configured");
            if (allOfferings.isEmpty()) issues.add("no offerings configured (add offerings to host servers)");

            if (!issues.isEmpty()) {
                String msg = "Nothing to test: " + String.join(", ", issues) + ".";
                log.warn("Test run {}: {}", testRun.getId(), msg);
                testRun.setStatus(TestRun.Status.COMPLETED);
                testRun.setCompletedAt(Instant.now());
                testRun.setStatusMessage(msg);
                testRunRepository.save(testRun);
                return CompletableFuture.completedFuture(null);
            }

            final List<Long> offeringIds = allOfferings.stream().map(Offering::getId).toList();
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (TestUser testUser : allTestUsers) {
                final Long userId = testUser.getId();
                final String userName = testUser.getName();
                final Long testRunId = testRun.getId();

                CompletableFuture<Void> userFuture = CompletableFuture.runAsync(() -> {
                    log.debug("Test run {}: Processing test user '{}'", testRunId, userName);
                    for (Long offeringId : offeringIds) {
                        runSingleTest(testRunId, userId, offeringId);
                    }
                }, testExecutor);

                futures.add(userFuture);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Check for duplicate associationIds among test users from the same home server × offering
            checkDuplicateAssociationIds(testRun);

            long total = allTestUsers.size() * (long) allOfferings.size();
            testRun.setStatusMessage("Completed " + total + " test case(s) across "
                    + allTestUsers.size() + " user(s) and " + allOfferings.size() + " offering(s).");
            testRun.setStatus(TestRun.Status.COMPLETED);
            testRun.setCompletedAt(Instant.now());
            testRunRepository.save(testRun);
            log.info("Test run {} completed successfully", testRun.getId());

        } catch (Exception e) {
            log.error("Test run {} failed with exception", testRun.getId(), e);
            testRun.setStatus(TestRun.Status.FAILED);
            testRun.setCompletedAt(Instant.now());
            testRunRepository.save(testRun);
        }

        return CompletableFuture.completedFuture(null);
    }

    private void checkDuplicateAssociationIds(TestRun testRun) {
        List<TestResult> results = testResultRepository.findByTestRunIdWithDetails(testRun.getId());
        // Group by homeServerId:offeringId, collect associationIds
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (TestResult r : results) {
            if (r.getCapturedAssociationId() == null) continue;
            String key = r.getTestUser().getHomeServer().getId() + ":" + r.getOffering().getId();
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(r.getCapturedAssociationId());
        }
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : byKey.entrySet()) {
            long unique = new HashSet<>(entry.getValue()).size();
            if (unique < entry.getValue().size()) {
                warnings.add("Duplicate associationId for key " + entry.getKey());
                log.warn("Test run {}: Duplicate associationId detected for key {}", testRun.getId(), entry.getKey());
            }
        }
        if (!warnings.isEmpty()) {
            String existing = testRun.getStatusMessage() != null ? testRun.getStatusMessage() + " " : "";
            testRun.setStatusMessage(existing + "WARNING: duplicate associationIds: " + String.join(", ", warnings));
            testRunRepository.save(testRun);
        }
    }

    // ── Single test case ──────────────────────────────────────────────────────

    private void runSingleTest(Long testRunId, Long testUserId, Long offeringId) {
        TestRun testRun = testRunRepository.findById(testRunId).orElseThrow();
        TestUser testUser = testUserRepository.findByIdWithHomeServer(testUserId).orElseThrow();
        Offering offering = offeringRepository.findByIdWithHostServer(offeringId).orElseThrow();

        String correlationId = UUID.randomUUID().toString();

        TestResult result = new TestResult();
        result.setTestRun(testRun);
        result.setTestUser(testUser);
        result.setOffering(offering);
        result.setStartedAt(Instant.now());

        List<Map<String, Object>> steps = new ArrayList<>();

        try {
            HomeServer homeServer = testUser.getHomeServer();
            HostServer hostServer = offering.getHostServer();

            // ── Step 1: Get access token ──────────────────────────────────────
            Map<String, Object> tokenStep = newStep("getToken", "setup");
            tokenStep.put("testUser", testUser.getName());
            tokenStep.put("username", testUser.getUsername());

            String accessToken;
            String mockAccessToken = null;
            long tokenStart = mark();
            try {
                accessToken = tokenService.getAccessToken(testUser);
                setDuration(tokenStep, tokenStart);
                tokenStep.put("status", "success");
                log.debug("Test run {}: Got token for user '{}'", testRunId, testUser.getName());
                if (mockUserSuffix != null && !mockUserSuffix.isBlank()) {
                    mockAccessToken = tokenService.getMockAccessToken(testUser, null);
                    log.debug("Test run {}: Got mock token for user '{}'", testRunId, testUser.getName());
                }
            } catch (Exception e) {
                setDuration(tokenStep, tokenStart);
                tokenStep.put("status", "error");
                tokenStep.put("error", e.getMessage());
                steps.add(tokenStep);
                saveErrorResult(result, steps, "Failed to get access token: " + e.getMessage());
                return;
            }
            steps.add(tokenStep);

            // ── Step 2: POST /persons/me on Home Server (direct, real token) ──
            Map<String, Object> personsStep = newStep("getPersonId", "setup");
            personsStep.put("url", homeServer.getUrl() + "/persons/me");

            String personId;
            long personsStart = mark();
            try {
                HttpHeaders headers = headersWithAuth(accessToken,
                        homeServer.getBasicAuthUsername(), homeServer.getBasicAuthPassword());
                HttpEntity<Void> entity = new HttpEntity<>(headers);
                ResponseEntity<Map> response = withRetry(() ->
                        standardRestTemplate.exchange(homeServer.getUrl() + "/persons/me",
                                HttpMethod.POST, entity, Map.class));
                setDuration(personsStep, personsStart);
                Map<String, Object> personResponse = response.getBody() != null ? response.getBody() : Collections.emptyMap();
                personId = extractPersonId(personResponse);
                if (personId == null) {
                    personsStep.put("status", "error");
                    personsStep.put("responseBody", personResponse.toString());
                    personsStep.put("error", "No personId found in response");
                    steps.add(personsStep);
                    saveErrorResult(result, steps, "No personId in /persons/me response");
                    return;
                }
                personsStep.put("status", "success");
                personsStep.put("personId", personId);
                personsStep.put("httpStatus", response.getStatusCode().value());
            } catch (HttpStatusCodeException e) {
                setDuration(personsStep, personsStart);
                personsStep.put("status", "error");
                personsStep.put("httpStatus", e.getStatusCode().value());
                personsStep.put("error", e.getResponseBodyAsString());
                steps.add(personsStep);
                saveErrorResult(result, steps, "POST /persons/me failed: HTTP " + e.getStatusCode());
                return;
            } catch (Exception e) {
                setDuration(personsStep, personsStart);
                personsStep.put("status", "error");
                personsStep.put("error", e.getMessage());
                steps.add(personsStep);
                saveErrorResult(result, steps, "POST /persons/me failed: " + e.getMessage());
                return;
            }
            steps.add(personsStep);

            // ── Steps 3a–3d: Broker enrollment ───────────────────────────────
            String associationId = null;
            TestResult.ActualResult actualResult = TestResult.ActualResult.ERROR;

            String brokerMockUsername = mockAccessToken != null ? tokenService.getMockUsername(testUser) : null;
            String brokerMockClaims   = mockAccessToken != null ? tokenService.getMockClaims(testUser) : null;

            if ("BROKER".equalsIgnoreCase(hostServer.getEnrollmentMode())) {
                BrokerResult brokerResult = performBrokerEnrollment(testRunId, testUser, offering,
                        homeServer, hostServer, steps,
                        mockAccessToken != null ? accessToken : null,
                        brokerMockUsername, brokerMockClaims);
                actualResult = brokerResult.result();
                if (brokerResult.proxySessionId() != null) {
                    String captureKey = homeServer.getId() + ":" + brokerResult.proxySessionId();
                    associationId = captureStore.lookup(captureKey);
                    captureStore.remove(captureKey);
                    realTokenStore.remove(brokerResult.proxySessionId());
                    if (associationId != null) {
                        log.debug("Test run {}: broker captured associationId={}", testRunId, associationId);
                        result.setCapturedAssociationId(associationId);
                    } else {
                        log.warn("Test run {}: broker proxy returned no associationId (key={})",
                                testRunId, captureKey);
                    }
                }
            }

            // ── Duplicate enrollment check ────────────────────────────────────
            if (actualResult == TestResult.ActualResult.SUCCESS) {
                Map<String, Object> dupStep = newStep("duplicateEnrollment", "duplicate");
                log.info("Test run {}: Attempting duplicate enrollment for user '{}' x offering '{}'",
                        testRunId, testUser.getName(), offering.getName());

                BrokerResult dupResult = performBrokerEnrollment(testRunId, testUser, offering,
                        homeServer, hostServer, new ArrayList<>(),
                        mockAccessToken != null ? accessToken : null,
                        brokerMockUsername, brokerMockClaims);

                if (dupResult.proxySessionId() != null) {
                    String dupKey = homeServer.getId() + ":" + dupResult.proxySessionId();
                    captureStore.remove(dupKey);
                    realTokenStore.remove(dupResult.proxySessionId());
                }

                dupStep.put("expected", "DENIED");
                dupStep.put("actual", dupResult.result().name());
                if (dupResult.result() == TestResult.ActualResult.DENIED) {
                    dupStep.put("status", "success");
                    log.info("Test run {}: Duplicate enrollment correctly denied", testRunId);
                } else {
                    dupStep.put("status", "error");
                    dupStep.put("error", "Expected DENIED but got " + dupResult.result());
                    log.warn("Test run {}: Duplicate enrollment expected DENIED but got {}",
                            testRunId, dupResult.result());
                }
                steps.add(dupStep);
            }

            // ── Steps 4a–4d: Verification (direct calls, real token) ──────────
            String verifyAssocId  = associationId;
            String verifyBaseUrl  = homeServer.getUrl();
            String verifyBasicUser = homeServer.getBasicAuthUsername();
            String verifyBasicPass = homeServer.getBasicAuthPassword();

            if (actualResult == TestResult.ActualResult.SUCCESS && verifyAssocId != null) {
                String assocUrl = verifyBaseUrl + "/associations/" + verifyAssocId;

                // 4a: PATCH remoteState → associated
                Map<String, Object> patchStateStep = newStep("patchRemoteStateAssociated", "verification");
                patchStateStep.put("url", assocUrl);
                long ps = mark();
                try {
                    HttpHeaders h = headersWithAuth(accessToken, verifyBasicUser, verifyBasicPass);
                    String body = objectMapper.writeValueAsString(Map.of("remoteState", "associated"));
                    ResponseEntity<Map> resp = withRetry(() ->
                            standardRestTemplate.exchange(assocUrl, HttpMethod.PATCH,
                                    new HttpEntity<>(body, h), Map.class));
                    setDuration(patchStateStep, ps);
                    patchStateStep.put("status", "success");
                    patchStateStep.put("httpStatus", resp.getStatusCode().value());
                } catch (Exception e) {
                    setDuration(patchStateStep, ps);
                    patchStateStep.put("status", "error");
                    patchStateStep.put("error", e.getMessage());
                    log.warn("Test run {}: PATCH remoteState=associated failed for {}: {}",
                            testRunId, associationId, e.getMessage());
                }
                steps.add(patchStateStep);

                // 4b: GET and verify remoteState
                Map<String, Object> verifyStateStep = newStep("verifyRemoteStateAssociated", "verification");
                verifyStateStep.put("url", assocUrl);
                long vs = mark();
                try {
                    HttpHeaders gh = headersWithAuth(accessToken, verifyBasicUser, verifyBasicPass);
                    ResponseEntity<Map> gr = withRetry(() ->
                            standardRestTemplate.exchange(assocUrl, HttpMethod.GET,
                                    new HttpEntity<>(gh), Map.class));
                    setDuration(verifyStateStep, vs);
                    Map<String, Object> assocBody = gr.getBody() != null ? gr.getBody() : Collections.emptyMap();
                    verifyStateStep.put("httpStatus", gr.getStatusCode().value());
                    Object savedState = assocBody.get("remoteState");
                    verifyStateStep.put("savedRemoteState", savedState);
                    if ("associated".equalsIgnoreCase(savedState != null ? savedState.toString() : null)) {
                        verifyStateStep.put("status", "success");
                    } else {
                        verifyStateStep.put("status", "mismatch");
                        verifyStateStep.put("mismatches", Map.of("remoteState", Map.of("sent", "associated", "saved", savedState)));
                        log.warn("Test run {}: remoteState mismatch: expected=associated, actual={}", testRunId, savedState);
                    }
                } catch (Exception e) {
                    setDuration(verifyStateStep, vs);
                    verifyStateStep.put("status", "error");
                    verifyStateStep.put("error", e.getMessage());
                }
                steps.add(verifyStateStep);

                // 4c–4d: Result field verification (repeated per configured result)
                List<Result> hostResults = resultRepository.findByHostServer(hostServer);
                if (hostResults.isEmpty()) {
                    Map<String, Object> noResult = newStep("noResultConfigured", "verification");
                    noResult.put("status", "skipped");
                    noResult.put("note", "No result objects configured for this host server — skipping result field verification");
                    steps.add(noResult);
                }
                for (Result resultConfig : hostResults) {
                    Map<String, Object> resultData = buildResultData(resultConfig);

                    Map<String, Object> patchResultStep = newStep("patchAssociationResult", "verification");
                    patchResultStep.put("resultName", resultConfig.getName());
                    patchResultStep.put("url", assocUrl);
                    patchResultStep.put("sentResult", new LinkedHashMap<>(resultData));
                    long pr = mark();
                    try {
                        HttpHeaders h = headersWithAuth(accessToken, verifyBasicUser, verifyBasicPass);
                        String body = objectMapper.writeValueAsString(Map.of("result", resultData));
                        ResponseEntity<Map> resp = withRetry(() ->
                                standardRestTemplate.exchange(assocUrl, HttpMethod.PATCH,
                                        new HttpEntity<>(body, h), Map.class));
                        setDuration(patchResultStep, pr);
                        patchResultStep.put("status", "success");
                        patchResultStep.put("httpStatus", resp.getStatusCode().value());
                    } catch (Exception e) {
                        setDuration(patchResultStep, pr);
                        patchResultStep.put("status", "error");
                        patchResultStep.put("error", e.getMessage());
                        log.warn("Test run {}: PATCH result '{}' failed for {}: {}",
                                testRunId, resultConfig.getName(), associationId, e.getMessage());
                    }
                    steps.add(patchResultStep);

                    Map<String, Object> verifyResultStep = newStep("verifyAssociationResult", "verification");
                    verifyResultStep.put("resultName", resultConfig.getName());
                    verifyResultStep.put("url", assocUrl);
                    long vr = mark();
                    try {
                        HttpHeaders gh = headersWithAuth(accessToken, verifyBasicUser, verifyBasicPass);
                        ResponseEntity<Map> gr = withRetry(() ->
                                standardRestTemplate.exchange(assocUrl, HttpMethod.GET,
                                        new HttpEntity<>(gh), Map.class));
                        setDuration(verifyResultStep, vr);
                        Map<String, Object> assocBody = gr.getBody() != null ? gr.getBody() : Collections.emptyMap();
                        verifyResultStep.put("httpStatus", gr.getStatusCode().value());
                        @SuppressWarnings("unchecked")
                        Map<String, Object> savedResult = assocBody.containsKey("result")
                                ? (Map<String, Object>) assocBody.get("result") : assocBody;
                        Map<String, Object> mismatches = new LinkedHashMap<>();
                        checkField(mismatches, "state",      resultData.get("state"),      savedResult.get("state"));
                        checkField(mismatches, "pass",       resultData.get("pass"),        savedResult.get("pass"));
                        checkField(mismatches, "comment",    resultData.get("comment"),     savedResult.get("comment"));
                        checkField(mismatches, "score",      resultData.get("score"),       savedResult.get("score"));
                        checkField(mismatches, "resultDate", resultData.get("resultDate"),  savedResult.get("resultDate"));
                        checkField(mismatches, "studyLoad",  resultData.get("studyLoad"),   savedResult.get("studyLoad"));
                        if (mismatches.isEmpty()) {
                            verifyResultStep.put("status", "success");
                            verifyResultStep.put("message", "All result fields match");
                        } else {
                            verifyResultStep.put("status", "mismatch");
                            verifyResultStep.put("mismatches", mismatches);
                            log.warn("Test run {}: Result '{}' mismatch for {}: {}",
                                    testRunId, resultConfig.getName(), associationId, mismatches);
                        }
                        verifyResultStep.put("savedResult", savedResult);
                    } catch (Exception e) {
                        setDuration(verifyResultStep, vr);
                        verifyResultStep.put("status", "error");
                        verifyResultStep.put("error", e.getMessage());
                    }
                    steps.add(verifyResultStep);
                }
            }

            // ── Step 5: Cancel association (cleanup) ──────────────────────────
            if (verifyAssocId != null) {
                String cancelUrl = verifyBaseUrl + "/associations/" + verifyAssocId;
                Map<String, Object> cancelStep = newStep("cancelAssociation", "cleanup");
                cancelStep.put("url", cancelUrl);
                long cs = mark();
                try {
                    HttpHeaders h = headersWithAuth(accessToken, verifyBasicUser, verifyBasicPass);
                    String body = objectMapper.writeValueAsString(Map.of("remoteState", "canceled"));
                    ResponseEntity<Map> resp = withRetry(() ->
                            standardRestTemplate.exchange(cancelUrl, HttpMethod.PATCH,
                                    new HttpEntity<>(body, h), Map.class));
                    setDuration(cancelStep, cs);
                    cancelStep.put("status", "success");
                    cancelStep.put("httpStatus", resp.getStatusCode().value());
                } catch (Exception e) {
                    setDuration(cancelStep, cs);
                    cancelStep.put("status", "error");
                    cancelStep.put("error", e.getMessage());
                    log.warn("Test run {}: Cancel PATCH failed for {}: {}", testRunId, associationId, e.getMessage());
                }
                steps.add(cancelStep);

                // 5b: Verify cancel was persisted
                Map<String, Object> verifyCancelStep = newStep("verifyCanceled", "cleanup");
                verifyCancelStep.put("url", cancelUrl);
                long vcs = mark();
                try {
                    HttpHeaders gh = headersWithAuth(accessToken, verifyBasicUser, verifyBasicPass);
                    ResponseEntity<Map> gr = withRetry(() ->
                            standardRestTemplate.exchange(cancelUrl, HttpMethod.GET,
                                    new HttpEntity<>(gh), Map.class));
                    setDuration(verifyCancelStep, vcs);
                    Map<String, Object> body = gr.getBody() != null ? gr.getBody() : Collections.emptyMap();
                    Object savedState = body.get("remoteState");
                    verifyCancelStep.put("httpStatus", gr.getStatusCode().value());
                    verifyCancelStep.put("savedRemoteState", savedState);
                    if ("canceled".equalsIgnoreCase(savedState != null ? savedState.toString() : null)) {
                        verifyCancelStep.put("status", "success");
                    } else {
                        verifyCancelStep.put("status", "mismatch");
                        verifyCancelStep.put("mismatches", Map.of("remoteState",
                                Map.of("sent", "canceled", "saved", savedState)));
                        log.warn("Test run {}: verifyCanceled mismatch: actual={}", testRunId, savedState);
                    }
                } catch (Exception e) {
                    setDuration(verifyCancelStep, vcs);
                    verifyCancelStep.put("status", "error");
                    verifyCancelStep.put("error", e.getMessage());
                }
                steps.add(verifyCancelStep);

                // 5c: Attempt to update a canceled association (home server should reject)
                Map<String, Object> postCancelStep = newStep("postCancelUpdateAttempt", "cleanup");
                postCancelStep.put("url", cancelUrl);
                postCancelStep.put("note", "Attempting to update a canceled association — home server should reject");
                long pcs = mark();
                try {
                    HttpHeaders h = headersWithAuth(accessToken, verifyBasicUser, verifyBasicPass);
                    String body = objectMapper.writeValueAsString(Map.of("remoteState", "associated"));
                    ResponseEntity<Map> resp = withRetry(() ->
                            standardRestTemplate.exchange(cancelUrl, HttpMethod.PATCH,
                                    new HttpEntity<>(body, h), Map.class));
                    setDuration(postCancelStep, pcs);
                    int sc = resp.getStatusCode().value();
                    postCancelStep.put("httpStatus", sc);
                    if (sc >= 400) {
                        postCancelStep.put("status", "success");
                        postCancelStep.put("note", "Correctly rejected update on canceled association (HTTP " + sc + ")");
                    } else {
                        postCancelStep.put("status", "warning");
                        postCancelStep.put("note", "Accepted update on canceled association (HTTP " + sc + ") — home server may not enforce state-machine transitions");
                    }
                } catch (HttpStatusCodeException e) {
                    setDuration(postCancelStep, pcs);
                    postCancelStep.put("httpStatus", e.getStatusCode().value());
                    if (e.getStatusCode().value() >= 400) {
                        postCancelStep.put("status", "success");
                        postCancelStep.put("note", "Correctly rejected (HTTP " + e.getStatusCode().value() + ")");
                    } else {
                        postCancelStep.put("status", "error");
                        postCancelStep.put("error", e.getMessage());
                    }
                } catch (Exception e) {
                    setDuration(postCancelStep, pcs);
                    postCancelStep.put("status", "error");
                    postCancelStep.put("error", e.getMessage());
                }
                steps.add(postCancelStep);
            }

            // ── Boundary tests (only in BROKER mode) ─────────────────────────
            if ("BROKER".equalsIgnoreCase(hostServer.getEnrollmentMode())) {
                // Invalid Basic Auth test (only if host server has Basic Auth configured)
                if (hostServer.getBasicAuthUsername() != null && !hostServer.getBasicAuthUsername().isBlank()) {
                    testInvalidBasicAuth(testRunId, testUser, offering, homeServer, hostServer, steps,
                            mockAccessToken != null ? accessToken : null, brokerMockUsername, brokerMockClaims);
                }
                // Missing offering data test
                testMissingOfferingData(testRunId, testUser, offering, homeServer, hostServer, steps,
                        mockAccessToken != null ? accessToken : null, brokerMockUsername, brokerMockClaims);
            }

            // ── Save result ───────────────────────────────────────────────────
            boolean hasWarnings = steps.stream().anyMatch(s -> "warning".equals(s.get("status")));
            result.setHasWarnings(hasWarnings);
            result.setActualResult(actualResult);
            result.setCompletedAt(Instant.now());
            try { result.setStepDetails(objectMapper.writeValueAsString(steps)); }
            catch (Exception e) { result.setStepDetails("[]"); }
            testResultRepository.save(result);

            log.debug("Test run {}: user '{}' x offering '{}': actual={}",
                    testRunId, testUser.getName(), offering.getName(), actualResult);

        } catch (Exception e) {
            log.error("Test run {}: Unexpected error for user '{}' x offering '{}'",
                    testRunId, testUser.getName(), offering.getName(), e);
            saveErrorResult(result, steps, "Unexpected error: " + e.getMessage());
        }
    }

    // ── Broker enrollment ─────────────────────────────────────────────────────

    private record BrokerResult(TestResult.ActualResult result, String proxySessionId) {}

    private record CorrelationResult(String correlationId, String proxySessionId, boolean failed) {
        static CorrelationResult failed(String proxySessionId) { return new CorrelationResult(null, proxySessionId, true); }
        static CorrelationResult of(String cid, String psid)   { return new CorrelationResult(cid, psid, false); }
    }

    /**
     * Runs steps 3a–3c to obtain a correlationId from the enrollment receiver.
     * Does NOT add steps to the provided list — this is used by boundary tests as infrastructure.
     */
    private CorrelationResult acquireCorrelationId(
            Long testRunId, TestUser testUser, HomeServer homeServer, HostServer hostServer,
            String realAccessToken, String mockUsername, String mockClaims) {

        String proxySessionId = UUID.randomUUID().toString();
        String proxyHomeInstitution = autotestBaseUrl + "/ooapi-proxy/" + homeServer.getId() + "/" + proxySessionId;

        if (realAccessToken != null) realTokenStore.store(proxySessionId, realAccessToken);

        String scope = (hostServer.getBrokerScope() != null && !hostServer.getBrokerScope().isBlank())
                ? hostServer.getBrokerScope() : "offline_access";

        // 3a: POST /api/enrollment
        String enrollUrl = hostServer.getUrl() + "/api/enrollment";
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("personURI", autotestBaseUrl + "/ooapi-proxy/" + homeServer.getId() + "/" + proxySessionId + "/0/persons/me");
        formData.add("personAuth", "HEADER");
        formData.add("homeInstitution", proxyHomeInstitution);
        formData.add("scope", scope);

        HttpHeaders enrollHeaders = new HttpHeaders();
        enrollHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String oauthUrl = getNoRedirectLocation(HttpMethod.POST, enrollUrl,
                new HttpEntity<>(formData, enrollHeaders));
        if (oauthUrl == null || extractQueryParam(oauthUrl, "error") != null) {
            realTokenStore.remove(proxySessionId);
            return CorrelationResult.failed(proxySessionId);
        }

        String state = extractQueryParam(oauthUrl, "state");
        if (state == null) { realTokenStore.remove(proxySessionId); return CorrelationResult.failed(proxySessionId); }

        // 3b: POST to mock OAuth
        String oauthUsername = mockUsername != null ? mockUsername : testUser.getUsername();
        String oauthClaims   = mockClaims   != null ? mockClaims   : (testUser.getClaims() != null ? testUser.getClaims() : "{}");

        MultiValueMap<String, String> oauthBody = new LinkedMultiValueMap<>();
        oauthBody.add("username", oauthUsername);
        oauthBody.add("claims", oauthClaims);
        HttpHeaders oauthHeaders = new HttpHeaders();
        oauthHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String callbackLocation = getNoRedirectLocation(HttpMethod.POST, oauthUrl,
                new HttpEntity<>(oauthBody, oauthHeaders));
        if (callbackLocation == null) { realTokenStore.remove(proxySessionId); return CorrelationResult.failed(proxySessionId); }

        String code = extractQueryParam(callbackLocation, "code");
        if (code == null) { realTokenStore.remove(proxySessionId); return CorrelationResult.failed(proxySessionId); }

        // 3c: GET /redirect_uri
        String callbackUrl;
        try {
            callbackUrl = hostServer.getUrl() + "/redirect_uri?code="
                    + URLEncoder.encode(code, StandardCharsets.UTF_8)
                    + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
        } catch (Exception e) { realTokenStore.remove(proxySessionId); return CorrelationResult.failed(proxySessionId); }

        String brokerRedirect = getNoRedirectLocation(HttpMethod.GET, callbackUrl, new HttpEntity<>(new HttpHeaders()));
        if (brokerRedirect == null) { realTokenStore.remove(proxySessionId); return CorrelationResult.failed(proxySessionId); }

        String correlationId = extractQueryParam(brokerRedirect, "correlationID");
        if (correlationId == null) { realTokenStore.remove(proxySessionId); return CorrelationResult.failed(proxySessionId); }

        return CorrelationResult.of(correlationId, proxySessionId);
    }

    /**
     * Full broker flow (3a–3d). Steps 3a–3c are implemented via acquireCorrelationId
     * and then step 3d (POST /api/start) is executed here with the full offering body.
     */
    private BrokerResult performBrokerEnrollment(
            Long testRunId, TestUser testUser, Offering offering,
            HomeServer homeServer, HostServer hostServer,
            List<Map<String, Object>> steps,
            String realAccessToken, String brokerMockUsername, String brokerMockClaims) {

        String proxySessionId = UUID.randomUUID().toString();
        String proxyHomeInstitution = autotestBaseUrl + "/ooapi-proxy/" + homeServer.getId() + "/" + proxySessionId;

        if (realAccessToken != null) realTokenStore.store(proxySessionId, realAccessToken);

        String scope = (hostServer.getBrokerScope() != null && !hostServer.getBrokerScope().isBlank())
                ? hostServer.getBrokerScope() : "offline_access";

        // 3a: POST /api/enrollment
        Map<String, Object> step3a = newStep("brokerInitEnrollment", "enrollment");
        String enrollUrl = hostServer.getUrl() + "/api/enrollment";
        step3a.put("url", enrollUrl);
        step3a.put("proxySessionId", proxySessionId);

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("personURI", autotestBaseUrl + "/ooapi-proxy/" + homeServer.getId() + "/" + proxySessionId + "/" + testRunId + "/persons/me");
        formData.add("personAuth", "HEADER");
        formData.add("homeInstitution", proxyHomeInstitution);
        formData.add("scope", scope);

        HttpHeaders enrollHeaders = new HttpHeaders();
        enrollHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        long s3a = mark();
        String oauthUrl = getNoRedirectLocation(HttpMethod.POST, enrollUrl,
                new HttpEntity<>(formData, enrollHeaders));
        setDuration(step3a, s3a);

        if (oauthUrl == null) {
            step3a.put("status", "error");
            step3a.put("error", "Expected 302 redirect to OAuth from /api/enrollment — check service registry config");
            steps.add(step3a);
            return new BrokerResult(TestResult.ActualResult.ERROR, null);
        }
        String brokerError = extractQueryParam(oauthUrl, "error");
        if (brokerError != null) {
            step3a.put("status", "error");
            step3a.put("brokerErrorCode", brokerError);
            step3a.put("error", "Enrollment rejected with error code " + brokerError
                    + " (412 = service registry validation failed)");
            steps.add(step3a);
            return new BrokerResult(isDeniedCode(parseIntSafe(brokerError))
                    ? TestResult.ActualResult.DENIED : TestResult.ActualResult.ERROR, null);
        }
        step3a.put("oauthUrl", oauthUrl);
        step3a.put("status", "success");
        steps.add(step3a);

        String state = extractQueryParam(oauthUrl, "state");
        if (state == null) {
            Map<String, Object> errStep = newStep("brokerExtractState", "enrollment");
            errStep.put("status", "error");
            errStep.put("error", "No 'state' param in OAuth URL: " + oauthUrl);
            steps.add(errStep);
            return new BrokerResult(TestResult.ActualResult.ERROR, null);
        }

        // 3b: POST to mock OAuth
        String oauthUsername = brokerMockUsername != null ? brokerMockUsername : testUser.getUsername();
        String oauthClaims   = brokerMockClaims   != null ? brokerMockClaims   : (testUser.getClaims() != null ? testUser.getClaims() : "{}");

        Map<String, Object> step3b = newStep("brokerOAuthLogin", "enrollment");
        step3b.put("url", oauthUrl);
        step3b.put("username", oauthUsername);

        MultiValueMap<String, String> oauthBody = new LinkedMultiValueMap<>();
        oauthBody.add("username", oauthUsername);
        oauthBody.add("claims", oauthClaims);
        HttpHeaders oauthHeaders = new HttpHeaders();
        oauthHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        long s3b = mark();
        String oauthCallbackLocation = getNoRedirectLocation(HttpMethod.POST, oauthUrl,
                new HttpEntity<>(oauthBody, oauthHeaders));
        setDuration(step3b, s3b);

        if (oauthCallbackLocation == null) {
            step3b.put("status", "error");
            step3b.put("error", "Expected 302 redirect with code from Mock OAuth");
            steps.add(step3b);
            return new BrokerResult(TestResult.ActualResult.ERROR, null);
        }
        String code = extractQueryParam(oauthCallbackLocation, "code");
        if (code == null) {
            step3b.put("status", "error");
            step3b.put("error", "No 'code' in OAuth callback Location: " + oauthCallbackLocation);
            steps.add(step3b);
            return new BrokerResult(TestResult.ActualResult.ERROR, null);
        }
        step3b.put("status", "success");
        steps.add(step3b);

        // 3c: GET /redirect_uri
        Map<String, Object> step3c = newStep("brokerRedirectCallback", "enrollment");
        String callbackUrl;
        try {
            callbackUrl = hostServer.getUrl() + "/redirect_uri?code="
                    + URLEncoder.encode(code, StandardCharsets.UTF_8)
                    + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
        } catch (Exception e) {
            step3c.put("status", "error");
            step3c.put("error", "Failed to encode callback URL: " + e.getMessage());
            steps.add(step3c);
            return new BrokerResult(TestResult.ActualResult.ERROR, null);
        }
        step3c.put("url", callbackUrl);

        long s3c = mark();
        String brokerRedirect = getNoRedirectLocation(HttpMethod.GET, callbackUrl,
                new HttpEntity<>(new HttpHeaders()));
        setDuration(step3c, s3c);

        if (brokerRedirect == null) {
            step3c.put("status", "error");
            step3c.put("error", "Expected 302 redirect to broker URL from /redirect_uri");
            steps.add(step3c);
            return new BrokerResult(TestResult.ActualResult.ERROR, null);
        }
        String correlationId = extractQueryParam(brokerRedirect, "correlationID");
        if (correlationId == null) {
            step3c.put("status", "error");
            step3c.put("error", "No 'correlationID' in broker redirect: " + brokerRedirect);
            steps.add(step3c);
            return new BrokerResult(TestResult.ActualResult.ERROR, null);
        }
        step3c.put("brokerRedirect", brokerRedirect);
        step3c.put("correlationId", correlationId);
        step3c.put("status", "success");
        steps.add(step3c);

        // 3d: POST /api/start
        Map<String, Object> step3d = newStep("brokerStart", "enrollment");
        String startUrl = hostServer.getUrl() + "/api/start";
        step3d.put("url", startUrl);
        step3d.put("correlationId", correlationId);

        HttpHeaders startHeaders = new HttpHeaders();
        startHeaders.setContentType(MediaType.APPLICATION_JSON);
        startHeaders.set("X-Correlation-ID", correlationId);
        if (hostServer.getBasicAuthUsername() != null && !hostServer.getBasicAuthUsername().isBlank()
                && hostServer.getBasicAuthPassword() != null && !hostServer.getBasicAuthPassword().isBlank()) {
            startHeaders.setBasicAuth(hostServer.getBasicAuthUsername(), hostServer.getBasicAuthPassword());
        }

        Map<String, Object> offeringBody = buildOfferingBody(offering);
        long s3d = mark();
        try {
            String startBodyJson = objectMapper.writeValueAsString(offeringBody);
            ResponseEntity<Map> startResponse = withRetry(() ->
                    standardRestTemplate.exchange(startUrl, HttpMethod.POST,
                            new HttpEntity<>(startBodyJson, startHeaders), Map.class));
            setDuration(step3d, s3d);

            int statusCode = startResponse.getStatusCode().value();
            step3d.put("httpStatus", statusCode);

            if (startResponse.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> responseBody = startResponse.getBody();
                if (responseBody != null) step3d.put("responseBody", responseBody.toString());
                int bodyCode = extractBodyCode(responseBody);
                if (bodyCode > 0) step3d.put("bodyCode", bodyCode);
                if (bodyCode >= 400) {
                    step3d.put("status", isDeniedCode(bodyCode) ? "denied" : "error");
                    step3d.put("error", "Enrollment rejected with code " + bodyCode);
                    steps.add(step3d);
                    return new BrokerResult(isDeniedCode(bodyCode)
                            ? TestResult.ActualResult.DENIED : TestResult.ActualResult.ERROR, null);
                }
                step3d.put("status", "success");
                steps.add(step3d);
                return new BrokerResult(TestResult.ActualResult.SUCCESS, proxySessionId);
            } else if (isDeniedCode(statusCode)) {
                step3d.put("status", "denied");
                steps.add(step3d);
                return new BrokerResult(TestResult.ActualResult.DENIED, null);
            } else {
                step3d.put("status", "error");
                step3d.put("error", "HTTP " + statusCode);
                steps.add(step3d);
                return new BrokerResult(TestResult.ActualResult.ERROR, null);
            }
        } catch (HttpStatusCodeException e) {
            setDuration(step3d, s3d);
            int sc = e.getStatusCode().value();
            step3d.put("httpStatus", sc);
            step3d.put("responseBody", e.getResponseBodyAsString());
            if (isDeniedCode(sc)) {
                step3d.put("status", "denied");
                steps.add(step3d);
                return new BrokerResult(TestResult.ActualResult.DENIED, null);
            }
            step3d.put("status", "error");
            step3d.put("error", e.getResponseBodyAsString());
            steps.add(step3d);
            return new BrokerResult(TestResult.ActualResult.ERROR, null);
        } catch (Exception e) {
            setDuration(step3d, s3d);
            step3d.put("status", "error");
            step3d.put("error", e.getMessage());
            steps.add(step3d);
            log.error("Test run {}: Broker /api/start failed: {}", testRunId, e.getMessage());
            return new BrokerResult(TestResult.ActualResult.ERROR, null);
        }
    }

    // ── Boundary tests ────────────────────────────────────────────────────────

    private void testInvalidBasicAuth(
            Long testRunId, TestUser testUser, Offering offering,
            HomeServer homeServer, HostServer hostServer,
            List<Map<String, Object>> steps,
            String realAccessToken, String mockUsername, String mockClaims) {

        Map<String, Object> step = newStep("invalidBasicAuthTest", "boundary");
        step.put("note", "Calling /api/start with invalid Basic Auth — host server must reject with 401 or 403");

        CorrelationResult cor = acquireCorrelationId(testRunId, testUser, homeServer, hostServer,
                realAccessToken, mockUsername, mockClaims);
        if (cor.failed()) {
            realTokenStore.remove(cor.proxySessionId());
            step.put("status", "skipped");
            step.put("note", "Could not acquire correlationId for boundary test");
            steps.add(step);
            return;
        }

        String startUrl = hostServer.getUrl() + "/api/start";
        step.put("url", startUrl);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Correlation-ID", cor.correlationId());
        h.setBasicAuth(hostServer.getBasicAuthUsername(), hostServer.getBasicAuthPassword() + "_INVALID");

        long start = mark();
        try {
            ResponseEntity<Map> resp = standardRestTemplate.exchange(startUrl, HttpMethod.POST,
                    new HttpEntity<>(objectMapper.writeValueAsString(buildOfferingBody(offering)), h), Map.class);
            setDuration(step, start);
            int sc = resp.getStatusCode().value();
            step.put("httpStatus", sc);
            if (sc >= 400) {
                step.put("status", "success");
                step.put("note", "Correctly rejected invalid credentials (HTTP " + sc + ")");
            } else {
                step.put("status", "warning");
                step.put("note", "Host server accepted /api/start with invalid Basic Auth (HTTP " + sc + ") — credentials are not being validated");
            }
        } catch (HttpStatusCodeException e) {
            setDuration(step, start);
            int sc = e.getStatusCode().value();
            step.put("httpStatus", sc);
            if (sc == 401 || sc == 403) {
                step.put("status", "success");
                step.put("note", "Correctly rejected invalid credentials (HTTP " + sc + ")");
            } else {
                step.put("status", "warning");
                step.put("note", "Rejected with unexpected status HTTP " + sc + " (expected 401 or 403)");
            }
        } catch (Exception e) {
            setDuration(step, start);
            step.put("status", "error");
            step.put("error", e.getMessage());
        } finally {
            realTokenStore.remove(cor.proxySessionId());
            captureStore.remove(homeServer.getId() + ":" + cor.proxySessionId());
        }
        steps.add(step);
    }

    private void testMissingOfferingData(
            Long testRunId, TestUser testUser, Offering offering,
            HomeServer homeServer, HostServer hostServer,
            List<Map<String, Object>> steps,
            String realAccessToken, String mockUsername, String mockClaims) {

        Map<String, Object> step = newStep("missingOfferingDataTest", "boundary");
        step.put("note", "Calling /api/start with an empty offering body — host server should reject missing required fields");

        CorrelationResult cor = acquireCorrelationId(testRunId, testUser, homeServer, hostServer,
                realAccessToken, mockUsername, mockClaims);
        if (cor.failed()) {
            realTokenStore.remove(cor.proxySessionId());
            step.put("status", "skipped");
            step.put("note", "Could not acquire correlationId for boundary test");
            steps.add(step);
            return;
        }

        String startUrl = hostServer.getUrl() + "/api/start";
        step.put("url", startUrl);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Correlation-ID", cor.correlationId());
        if (hostServer.getBasicAuthUsername() != null && !hostServer.getBasicAuthUsername().isBlank()) {
            h.setBasicAuth(hostServer.getBasicAuthUsername(), hostServer.getBasicAuthPassword());
        }

        long start = mark();
        try {
            ResponseEntity<Map> resp = standardRestTemplate.exchange(startUrl, HttpMethod.POST,
                    new HttpEntity<>("{}", h), Map.class);
            setDuration(step, start);
            int sc = resp.getStatusCode().value();
            step.put("httpStatus", sc);
            int bodyCode = extractBodyCode(resp.getBody());
            if (bodyCode > 0) step.put("bodyCode", bodyCode);
            if (sc >= 400 || bodyCode >= 400) {
                step.put("status", "success");
                step.put("note", "Correctly rejected empty offering body");
            } else {
                step.put("status", "warning");
                step.put("note", "Host server accepted /api/start with empty offering body — offering fields may not be validated");
            }
        } catch (HttpStatusCodeException e) {
            setDuration(step, start);
            step.put("httpStatus", e.getStatusCode().value());
            step.put("status", "success");
            step.put("note", "Correctly rejected empty offering body (HTTP " + e.getStatusCode().value() + ")");
        } catch (Exception e) {
            setDuration(step, start);
            step.put("status", "error");
            step.put("error", e.getMessage());
        } finally {
            realTokenStore.remove(cor.proxySessionId());
            captureStore.remove(homeServer.getId() + ":" + cor.proxySessionId());
        }
        steps.add(step);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String getNoRedirectLocation(HttpMethod method, String url, HttpEntity<?> entity) {
        try {
            ResponseEntity<String> response = noRedirectRestTemplate.exchange(URI.create(url), method, entity, String.class);
            if (response.getStatusCode().value() / 100 == 3) {
                return response.getHeaders().getFirst(HttpHeaders.LOCATION);
            }
            log.warn("Expected 3xx from {} {}, got {}", method, url, response.getStatusCode());
            return null;
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() / 100 == 3) {
                return e.getResponseHeaders() != null
                        ? e.getResponseHeaders().getFirst(HttpHeaders.LOCATION) : null;
            }
            log.error("HTTP error from {} {}: {} - {}", method, url, e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.error("Error calling {} {}: {}", method, url, e.getMessage());
            return null;
        }
    }

    private String extractQueryParam(String url, String paramName) {
        if (url == null) return null;
        try {
            URI uri = new URI(url);
            String query = uri.getRawQuery();
            if (query == null) return null;
            for (String param : query.split("&")) {
                int eq = param.indexOf('=');
                if (eq > 0) {
                    String key = URLDecoder.decode(param.substring(0, eq), StandardCharsets.UTF_8);
                    if (paramName.equals(key)) {
                        return URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract param '{}' from URL: {}", paramName, url);
        }
        return null;
    }

    private boolean isDeniedCode(int code) { return code == 401 || code == 403 || code == 412; }

    private int extractBodyCode(Map<String, Object> body) {
        if (body == null) return 0;
        Object val = body.get("code");
        if (val == null) return 0;
        try { return val instanceof Number ? ((Number) val).intValue() : Integer.parseInt(val.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private void saveErrorResult(TestResult result, List<Map<String, Object>> steps, String errorMessage) {
        result.setActualResult(TestResult.ActualResult.ERROR);
        result.setErrorMessage(errorMessage);
        result.setCompletedAt(Instant.now());
        try { result.setStepDetails(objectMapper.writeValueAsString(steps)); }
        catch (Exception e) { result.setStepDetails("[]"); }
        testResultRepository.save(result);
    }

    private String extractPersonId(Map<String, Object> response) {
        if (response == null) return null;
        if (response.containsKey("personId")) return response.get("personId") != null ? response.get("personId").toString() : null;
        if (response.containsKey("person") && response.get("person") instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) response.get("person");
            if (p.containsKey("personId")) return p.get("personId") != null ? p.get("personId").toString() : null;
        }
        if (response.containsKey("id")) return response.get("id") != null ? response.get("id").toString() : null;
        return null;
    }

    private HttpHeaders headersWithAuth(String bearerToken, String basicUser, String basicPass) {
        HttpHeaders headers = new HttpHeaders();
        if (bearerToken != null) headers.setBearerAuth(bearerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (basicUser != null && !basicUser.isBlank() && basicPass != null && !basicPass.isBlank()) {
            headers.setBasicAuth(basicUser, basicPass);
        }
        return headers;
    }

    private Map<String, Object> buildOfferingBody(Offering offering) {
        if (offering.getOfferingData() != null && !offering.getOfferingData().isBlank()) {
            try { return objectMapper.readValue(offering.getOfferingData(), new TypeReference<>() {}); }
            catch (Exception ignored) {}
        }
        return Map.of("offeringId", offering.getOfferingId() != null ? offering.getOfferingId() : "");
    }

    private Map<String, Object> buildResultData(Result resultConfig) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (resultConfig.getState()   != null) data.put("state",   resultConfig.getState());
        if (resultConfig.getPass()    != null) data.put("pass",    resultConfig.getPass());
        if (resultConfig.getComment() != null) data.put("comment", resultConfig.getComment());
        if (resultConfig.getScore()   != null) data.put("score",   resultConfig.getScore());
        data.put("resultDate", resultConfig.getResultDate() != null
                ? resultConfig.getResultDate() : java.time.LocalDate.now().toString());
        if (resultConfig.getExt() != null) {
            try { data.put("ext", objectMapper.readValue(resultConfig.getExt(), Object.class)); }
            catch (Exception ignored) { data.put("ext", resultConfig.getExt()); }
        }
        if (resultConfig.getStudyLoad() != null) {
            try { data.put("studyLoad", objectMapper.readValue(resultConfig.getStudyLoad(), Object.class)); }
            catch (Exception ignored) { data.put("studyLoad", resultConfig.getStudyLoad()); }
        }
        return data;
    }

    private void checkField(Map<String, Object> mismatches, String field, Object sent, Object saved) {
        if (sent == null && saved == null) return;
        if (sent == null) { mismatches.put(field, Map.of("sent", "null", "saved", saved)); return; }
        if (saved == null) { mismatches.put(field, Map.of("sent", sent, "saved", "null")); return; }
        try {
            // Structural equality via JsonNode — handles different field ordering in objects
            if (!objectMapper.valueToTree(sent).equals(objectMapper.valueToTree(saved))) {
                mismatches.put(field, Map.of("sent", sent, "saved", saved));
            }
        } catch (Exception e) {
            if (!sent.toString().equals(saved.toString())) {
                mismatches.put(field, Map.of("sent", sent, "saved", saved));
            }
        }
    }
}
