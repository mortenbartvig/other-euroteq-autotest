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

    @Async("testExecutor")
    public CompletableFuture<Void> runTests(TestRun testRun) {
        log.info("Starting test run {}", testRun.getId());

        try {
            testRun.setStatus(TestRun.Status.RUNNING);
            testRunRepository.save(testRun);

            // Load all home servers with test users
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

            // Group test users by their ID - each user group runs in parallel
            // Within each group, run all offerings sequentially
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            // Collect IDs only — entities become detached once the main thread's
            // session closes, so pass IDs and re-fetch inside each async thread.
            final List<Long> offeringIds = allOfferings.stream()
                    .map(Offering::getId).toList();

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

            // Wait for all parallel user groups to finish
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            allFutures.join();

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

    private void runSingleTest(Long testRunId, Long testUserId, Long offeringId) {
        // Re-fetch all entities with their associations in this thread's own session.
        TestRun testRun = testRunRepository.findById(testRunId).orElseThrow();
        TestUser testUser = testUserRepository.findByIdWithHomeServer(testUserId).orElseThrow();
        Offering offering = offeringRepository.findByIdWithHostServer(offeringId).orElseThrow();

        // Fresh correlation ID per test case for all host server calls.
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

            // Step 1: Get access token
            Map<String, Object> tokenStep = new LinkedHashMap<>();
            tokenStep.put("step", "getToken");
            tokenStep.put("testUser", testUser.getName());
            tokenStep.put("username", testUser.getUsername());

            String accessToken;
            String mockAccessToken = null;
            try {
                accessToken = tokenService.getAccessToken(testUser);
                tokenStep.put("status", "success");
                log.debug("Test run {}: Got token for user '{}'", testRunId, testUser.getName());
                if (mockUserSuffix != null && !mockUserSuffix.isBlank()) {
                    mockAccessToken = tokenService.getMockAccessToken(testUser, null);
                    log.debug("Test run {}: Got mock token for user '{}'", testRunId, testUser.getName());
                }
            } catch (Exception e) {
                tokenStep.put("status", "error");
                tokenStep.put("error", e.getMessage());
                steps.add(tokenStep);
                saveErrorResult(result, steps, "Failed to get access token: " + e.getMessage());
                return;
            }
            steps.add(tokenStep);

            // Step 2: POST to home server /persons/me
            Map<String, Object> personsStep = new LinkedHashMap<>();
            personsStep.put("step", "getPersonId");
            personsStep.put("url", homeServer.getUrl() + "/persons/me");

            String personId;
            Map<String, Object> personResponse;
            try {
                HttpHeaders headers = headersWithAuth(accessToken,
                        homeServer.getBasicAuthUsername(), homeServer.getBasicAuthPassword());
                HttpEntity<Void> entity = new HttpEntity<>(headers);

                ResponseEntity<Map> response = standardRestTemplate.exchange(
                        homeServer.getUrl() + "/persons/me",
                        HttpMethod.POST,
                        entity,
                        Map.class
                );

                personResponse = response.getBody() != null ? response.getBody() : Collections.emptyMap();
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
                personsStep.put("status", "error");
                personsStep.put("httpStatus", e.getStatusCode().value());
                personsStep.put("error", e.getResponseBodyAsString());
                steps.add(personsStep);
                saveErrorResult(result, steps, "POST /persons/me failed: HTTP " + e.getStatusCode());
                return;
            } catch (Exception e) {
                personsStep.put("status", "error");
                personsStep.put("error", e.getMessage());
                steps.add(personsStep);
                saveErrorResult(result, steps, "POST /persons/me failed: " + e.getMessage());
                return;
            }
            steps.add(personsStep);

            // Step 3: Enrollment — broker flow
            String associationId = null;
            TestResult.ActualResult actualResult = TestResult.ActualResult.ERROR;

            String brokerMockUsername = mockAccessToken != null ? tokenService.getMockUsername(testUser) : null;
            String brokerMockClaims = mockAccessToken != null ? tokenService.getMockClaims(testUser) : null;

            if ("BROKER".equalsIgnoreCase(hostServer.getEnrollmentMode())) {
                BrokerResult brokerResult = performBrokerEnrollment(testRunId, testUser, offering,
                        homeServer, hostServer, steps,
                        mockAccessToken != null ? accessToken : null,
                        brokerMockUsername, brokerMockClaims);
                actualResult = brokerResult.result;
                if (brokerResult.proxySessionId != null) {
                    String captureKey = homeServer.getId() + ":" + brokerResult.proxySessionId;
                    associationId = captureStore.lookup(captureKey);
                    captureStore.remove(captureKey);
                    realTokenStore.remove(brokerResult.proxySessionId);
                    if (associationId != null) {
                        log.debug("Test run {}: broker captured associationId={}", testRunId, associationId);
                    } else {
                        log.warn("Test run {}: broker proxy returned no associationId (key={})",
                                testRunId, captureKey);
                    }
                }
            }

            // Duplicate enrollment test: re-enroll the same user+offering, expect DENIED
            if (actualResult == TestResult.ActualResult.SUCCESS) {
                Map<String, Object> dupStep = new LinkedHashMap<>();
                dupStep.put("step", "duplicateEnrollment");
                log.info("Test run {}: Attempting duplicate enrollment for user '{}' x offering '{}'",
                        testRunId, testUser.getName(), offering.getName());

                BrokerResult dupResult = performBrokerEnrollment(testRunId, testUser, offering,
                        homeServer, hostServer, new ArrayList<>(),
                        mockAccessToken != null ? accessToken : null,
                        brokerMockUsername, brokerMockClaims);

                // Clean up stores from the duplicate attempt
                if (dupResult.proxySessionId != null) {
                    String dupKey = homeServer.getId() + ":" + dupResult.proxySessionId;
                    captureStore.remove(dupKey);
                    realTokenStore.remove(dupResult.proxySessionId);
                }

                dupStep.put("expected", "DENIED");
                dupStep.put("actual", dupResult.result.name());
                if (dupResult.result == TestResult.ActualResult.DENIED) {
                    dupStep.put("status", "success");
                    log.info("Test run {}: Duplicate enrollment correctly denied", testRunId);
                } else {
                    dupStep.put("status", "error");
                    dupStep.put("error", "Expected DENIED but got " + dupResult.result);
                    log.warn("Test run {}: Duplicate enrollment expected DENIED but got {}",
                            testRunId, dupResult.result);
                }
                steps.add(dupStep);
            }

            // Steps 4a-4d: run when enrollment succeeded and a result is configured for the offering.
            // Both DIRECT and BROKER modes call the home server directly using the associationId UUID.
            // In broker mode, associationId was captured by the OoapiProxyController intercepting the
            // inteken-ontvanger → home server POST /associations/external/me call.
            String verifyAssocId = associationId;
            String verifyBaseUrl = homeServer.getUrl();
            String verifyBasicUser = homeServer.getBasicAuthUsername();
            String verifyBasicPass = homeServer.getBasicAuthPassword();

            if (actualResult == TestResult.ActualResult.SUCCESS && verifyAssocId != null) {

                String assocUrl = verifyBaseUrl + "/associations/" + verifyAssocId;

                // Step 4a: PATCH remoteState → associated
                Map<String, Object> patchStateStep = new LinkedHashMap<>();
                patchStateStep.put("step", "patchRemoteStateAssociated");
                patchStateStep.put("url", assocUrl);
                try {
                    HttpHeaders headers = headersWithAuth(accessToken, verifyBasicUser, verifyBasicPass);
                    Map<String, Object> patchBody = new LinkedHashMap<>();
                    patchBody.put("remoteState", "associated");
                    HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(patchBody), headers);
                    ResponseEntity<Map> response = standardRestTemplate.exchange(
                            assocUrl, HttpMethod.PATCH, entity, Map.class);
                    patchStateStep.put("status", "success");
                    patchStateStep.put("httpStatus", response.getStatusCode().value());
                } catch (Exception e) {
                    patchStateStep.put("status", "error");
                    patchStateStep.put("error", e.getMessage());
                    log.warn("Test run {}: PATCH remoteState=associated failed for {}: {}",
                            testRunId, associationId, e.getMessage());
                }
                steps.add(patchStateStep);

                // Step 4b: GET and verify remoteState is "associated"
                Map<String, Object> verifyStateStep = new LinkedHashMap<>();
                verifyStateStep.put("step", "verifyRemoteStateAssociated");
                verifyStateStep.put("url", assocUrl);
                try {
                    HttpHeaders getHeaders = headersWithAuth(accessToken, verifyBasicUser, verifyBasicPass);
                    ResponseEntity<Map> getResponse = standardRestTemplate.exchange(
                            assocUrl, HttpMethod.GET, new HttpEntity<>(getHeaders), Map.class);
                    Map<String, Object> assocBody = getResponse.getBody() != null
                            ? getResponse.getBody() : Collections.emptyMap();
                    verifyStateStep.put("httpStatus", getResponse.getStatusCode().value());
                    Object savedRemoteState = assocBody.get("remoteState");
                    verifyStateStep.put("savedRemoteState", savedRemoteState);
                    if ("associated".equalsIgnoreCase(savedRemoteState != null ? savedRemoteState.toString() : null)) {
                        verifyStateStep.put("status", "success");
                    } else {
                        verifyStateStep.put("status", "mismatch");
                        verifyStateStep.put("expected", "associated");
                        verifyStateStep.put("actual", savedRemoteState);
                        log.warn("Test run {}: remoteState mismatch for {}: expected=associated, actual={}",
                                testRunId, associationId, savedRemoteState);
                    }
                } catch (Exception e) {
                    verifyStateStep.put("status", "error");
                    verifyStateStep.put("error", e.getMessage());
                    log.warn("Test run {}: GET for remoteState verification failed for {}: {}",
                            testRunId, associationId, e.getMessage());
                }
                steps.add(verifyStateStep);

                // Steps 4c-4d: repeat for every result configured on this host server
                List<Result> hostResults = resultRepository.findByHostServer(hostServer);
                for (Result resultConfig : hostResults) {
                    Map<String, Object> resultData = buildResultData(resultConfig);

                    Map<String, Object> patchResultStep = new LinkedHashMap<>();
                    patchResultStep.put("step", "patchAssociationResult");
                    patchResultStep.put("resultName", resultConfig.getName());
                    patchResultStep.put("url", assocUrl);
                    patchResultStep.put("sentResult", new LinkedHashMap<>(resultData));
                    try {
                        HttpHeaders headers = headersWithAuth(accessToken, verifyBasicUser, verifyBasicPass);
                        Map<String, Object> patchBody = new LinkedHashMap<>();
                        patchBody.put("result", resultData);
                        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(patchBody), headers);
                        ResponseEntity<Map> response = standardRestTemplate.exchange(
                                assocUrl, HttpMethod.PATCH, entity, Map.class);
                        patchResultStep.put("status", "success");
                        patchResultStep.put("httpStatus", response.getStatusCode().value());
                    } catch (Exception e) {
                        patchResultStep.put("status", "error");
                        patchResultStep.put("error", e.getMessage());
                        log.warn("Test run {}: PATCH result '{}' failed for {}: {}",
                                testRunId, resultConfig.getName(), associationId, e.getMessage());
                    }
                    steps.add(patchResultStep);

                    Map<String, Object> verifyResultStep = new LinkedHashMap<>();
                    verifyResultStep.put("step", "verifyAssociationResult");
                    verifyResultStep.put("resultName", resultConfig.getName());
                    verifyResultStep.put("url", assocUrl);
                    try {
                        HttpHeaders getHeaders = headersWithAuth(accessToken, verifyBasicUser, verifyBasicPass);
                        ResponseEntity<Map> getResponse = standardRestTemplate.exchange(
                                assocUrl, HttpMethod.GET, new HttpEntity<>(getHeaders), Map.class);
                        Map<String, Object> assocBody = getResponse.getBody() != null
                                ? getResponse.getBody() : Collections.emptyMap();
                        verifyResultStep.put("httpStatus", getResponse.getStatusCode().value());
                        @SuppressWarnings("unchecked")
                        Map<String, Object> savedResult = assocBody.containsKey("result")
                                ? (Map<String, Object>) assocBody.get("result")
                                : assocBody;
                        Map<String, Object> mismatches = new LinkedHashMap<>();
                        checkField(mismatches, "state", resultData.get("state"), savedResult.get("state"));
                        checkField(mismatches, "pass", resultData.get("pass"), savedResult.get("pass"));
                        checkField(mismatches, "comment", resultData.get("comment"), savedResult.get("comment"));
                        checkField(mismatches, "score", resultData.get("score"), savedResult.get("score"));
                        checkField(mismatches, "resultDate", resultData.get("resultDate"), savedResult.get("resultDate"));
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
                        verifyResultStep.put("status", "error");
                        verifyResultStep.put("error", e.getMessage());
                        log.warn("Test run {}: GET verify result '{}' failed for {}: {}",
                                testRunId, resultConfig.getName(), associationId, e.getMessage());
                    }
                    steps.add(verifyResultStep);
                }
            }

            // Step 5: Always PATCH /associations/{id} with remoteState: canceled
            if (verifyAssocId != null) {
                Map<String, Object> cancelStep = new LinkedHashMap<>();
                cancelStep.put("step", "cancelAssociation");
                String cancelUrl = verifyBaseUrl + "/associations/" + verifyAssocId;
                cancelStep.put("url", cancelUrl);

                try {
                    HttpHeaders headers = headersWithAuth(accessToken, verifyBasicUser, verifyBasicPass);

                    Map<String, Object> cancelBody = new LinkedHashMap<>();
                    cancelBody.put("remoteState", "canceled");
                    String cancelBodyJson = objectMapper.writeValueAsString(cancelBody);
                    HttpEntity<String> entity = new HttpEntity<>(cancelBodyJson, headers);

                    ResponseEntity<Map> response = standardRestTemplate.exchange(
                            cancelUrl,
                            HttpMethod.PATCH,
                            entity,
                            Map.class
                    );
                    cancelStep.put("status", "success");
                    cancelStep.put("httpStatus", response.getStatusCode().value());
                } catch (Exception e) {
                    cancelStep.put("status", "error");
                    cancelStep.put("error", e.getMessage());
                    log.warn("Test run {}: Cancel association PATCH failed for associationId {}: {}",
                            testRunId, associationId, e.getMessage());
                }
                steps.add(cancelStep);
            }

            // Save result
            result.setActualResult(actualResult);
            result.setCompletedAt(Instant.now());
            try {
                result.setStepDetails(objectMapper.writeValueAsString(steps));
            } catch (Exception e) {
                result.setStepDetails("[]");
            }
            testResultRepository.save(result);

            log.debug("Test run {}: Test user '{}' x offering '{}': actual={}",
                    testRunId, testUser.getName(), offering.getName(), actualResult);

        } catch (Exception e) {
            log.error("Test run {}: Unexpected error for user '{}' x offering '{}'",
                    testRunId, testUser.getName(), offering.getName(), e);
            saveErrorResult(result, steps, "Unexpected error: " + e.getMessage());
        }
    }

    private record BrokerResult(TestResult.ActualResult result, String proxySessionId) {}

    /**
     * Runs the full inteken-ontvanger broker enrollment flow:
     * 1. POST form to /api/enrollment → get redirect to OAuth authorization URL
     * 2. POST to mock OAuth with test user credentials → get code in redirect
     * 3. GET /redirect_uri?code=&state= on the inteken-ontvanger → get correlationID in redirect
     * 4. POST to /api/start with broker Basic Auth + X-Correlation-ID + offering body
     */
    private BrokerResult performBrokerEnrollment(
            Long testRunId, TestUser testUser, Offering offering,
            HomeServer homeServer, HostServer hostServer,
            List<Map<String, Object>> steps,
            String realAccessToken,
            String brokerMockUsername,
            String brokerMockClaims) {

        // Generate a unique session ID for this proxy intercept. The OoapiProxyController
        // captures the associationId under the key "{homeServerId}:{proxySessionId}".
        String proxySessionId = UUID.randomUUID().toString();
        String proxyHomeInstitution = autotestBaseUrl + "/ooapi-proxy/" + homeServer.getId() + "/" + proxySessionId;

        // Store the real access token so the proxy can swap it when the enrollment receiver
        // forwards the mock token to the home server.
        if (realAccessToken != null) {
            realTokenStore.store(proxySessionId, realAccessToken);
        }

        // --- 3a: POST form to /api/enrollment ---
        Map<String, Object> step3a = new LinkedHashMap<>();
        step3a.put("step", "brokerInitEnrollment");
        String enrollUrl = hostServer.getUrl() + "/api/enrollment";
        step3a.put("url", enrollUrl);
        step3a.put("proxySessionId", proxySessionId);

        // The inteken-ontvanger prepends "openid " to this value, so do NOT include openid here.
        // Use space-separated scopes as configured on the host server, e.g. "offline_access email dtu.dk/persons".
        String scope = (hostServer.getBrokerScope() != null && !hostServer.getBrokerScope().isBlank())
                ? hostServer.getBrokerScope() : "offline_access";

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("personURI", autotestBaseUrl + "/ooapi-proxy/" + homeServer.getId() + "/" + proxySessionId + "/" + testRunId + "/persons/me");
        formData.add("personAuth", "HEADER");
        // Use the autotest proxy as homeInstitution so the inteken-ontvanger calls
        // POST /associations/external/me on the proxy, which captures the associationId.
        formData.add("homeInstitution", proxyHomeInstitution);
        formData.add("scope", scope);

        HttpHeaders enrollHeaders = new HttpHeaders();
        enrollHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String oauthUrl = getNoRedirectLocation(HttpMethod.POST, enrollUrl,
                new HttpEntity<>(formData, enrollHeaders));
        if (oauthUrl == null) {
            step3a.put("status", "error");
            step3a.put("error", "Expected 302 redirect to OAuth from /api/enrollment — check service registry config");
            steps.add(step3a);
            return new BrokerResult(TestResult.ActualResult.ERROR, null);
        }
        // Detect broker error redirects: {brokerUrl}?error=<code> instead of OAuth authorize URL
        String brokerError = extractQueryParam(oauthUrl, "error");
        if (brokerError != null) {
            step3a.put("status", "error");
            step3a.put("brokerErrorCode", brokerError);
            step3a.put("error", "Enrollment rejected by inteken-ontvanger with error code "
                    + brokerError + " (e.g. 412 = invalid enrollment request / service registry validation failed)");
            steps.add(step3a);
            return new BrokerResult(isDeniedCode(parseIntSafe(brokerError))
                    ? TestResult.ActualResult.DENIED : TestResult.ActualResult.ERROR, null);
        }
        step3a.put("oauthUrl", oauthUrl);
        step3a.put("status", "success");
        steps.add(step3a);

        // Extract state from the OAuth authorization URL (base64-encoded enrollment request)
        String state = extractQueryParam(oauthUrl, "state");
        if (state == null) {
            Map<String, Object> errStep = new LinkedHashMap<>();
            errStep.put("step", "brokerExtractState");
            errStep.put("status", "error");
            errStep.put("error", "No 'state' param in OAuth URL: " + oauthUrl);
            steps.add(errStep);
            return new BrokerResult(TestResult.ActualResult.ERROR, null);
        }

        // --- 3b: POST to mock OAuth server to simulate user login ---
        String oauthUsername = brokerMockUsername != null ? brokerMockUsername : testUser.getUsername();
        String oauthClaims = brokerMockClaims != null ? brokerMockClaims : (testUser.getClaims() != null ? testUser.getClaims() : "{}");

        Map<String, Object> step3b = new LinkedHashMap<>();
        step3b.put("step", "brokerOAuthLogin");
        step3b.put("url", oauthUrl);
        step3b.put("username", oauthUsername);

        MultiValueMap<String, String> oauthBody = new LinkedMultiValueMap<>();
        oauthBody.add("username", oauthUsername);
        oauthBody.add("claims", oauthClaims);

        HttpHeaders oauthHeaders = new HttpHeaders();
        oauthHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String oauthCallbackLocation = getNoRedirectLocation(HttpMethod.POST, oauthUrl,
                new HttpEntity<>(oauthBody, oauthHeaders));
        if (oauthCallbackLocation == null) {
            step3b.put("status", "error");
            step3b.put("error", "Expected 302 redirect with code from OAuth mock");
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

        // --- 3c: Call inteken-ontvanger /redirect_uri to exchange code for enrollment ---
        Map<String, Object> step3c = new LinkedHashMap<>();
        step3c.put("step", "brokerRedirectCallback");

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

        String brokerRedirect = getNoRedirectLocation(HttpMethod.GET, callbackUrl,
                new HttpEntity<>(new HttpHeaders()));
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

        // --- 3d: POST to /api/start with broker Basic Auth + X-Correlation-ID ---
        Map<String, Object> step3d = new LinkedHashMap<>();
        step3d.put("step", "brokerStart");
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

        Map<String, Object> offeringBody;
        if (offering.getOfferingData() != null && !offering.getOfferingData().isBlank()) {
            try {
                offeringBody = objectMapper.readValue(offering.getOfferingData(), new TypeReference<>() {});
            } catch (Exception e) {
                offeringBody = Map.of("offeringId", offering.getOfferingId() != null ? offering.getOfferingId() : "");
            }
        } else {
            offeringBody = Map.of("offeringId", offering.getOfferingId() != null ? offering.getOfferingId() : "");
        }

        try {
            String startBodyJson = objectMapper.writeValueAsString(offeringBody);
            HttpEntity<String> startEntity = new HttpEntity<>(startBodyJson, startHeaders);

            ResponseEntity<Map> startResponse = standardRestTemplate.exchange(
                    startUrl, HttpMethod.POST, startEntity, Map.class);

            int statusCode = startResponse.getStatusCode().value();
            step3d.put("httpStatus", statusCode);

            if (startResponse.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> responseBody = startResponse.getBody();
                if (responseBody != null) {
                    step3d.put("responseBody", responseBody.toString());
                }
                // The inteken-ontvanger wraps backend errors (>= 400) as HTTP 200 with
                // a 'code' field in the JSON body. Check it explicitly.
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
            int statusCode = e.getStatusCode().value();
            step3d.put("httpStatus", statusCode);
            step3d.put("responseBody", e.getResponseBodyAsString());
            if (isDeniedCode(statusCode)) {
                step3d.put("status", "denied");
                steps.add(step3d);
                return new BrokerResult(TestResult.ActualResult.DENIED, null);
            } else {
                step3d.put("status", "error");
                step3d.put("error", e.getResponseBodyAsString());
                steps.add(step3d);
                return new BrokerResult(TestResult.ActualResult.ERROR, null);
            }
        } catch (Exception e) {
            step3d.put("status", "error");
            step3d.put("error", e.getMessage());
            steps.add(step3d);
            log.error("Test run {}: Broker /api/start failed: {}", testRunId, e.getMessage());
            return new BrokerResult(TestResult.ActualResult.ERROR, null);
        }
    }

    /**
     * Issues a no-redirect HTTP request and returns the Location header value from the 3xx response.
     * Uses URI.create() to pass a pre-encoded URI to RestTemplate, preventing double-encoding of
     * percent-encoded characters already present in the URL (e.g. %20 becoming %2520).
     */
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

    private boolean isDeniedCode(int code) {
        return code == 401 || code == 403 || code == 412;
    }

    private int extractBodyCode(Map<String, Object> body) {
        if (body == null) return 0;
        Object val = body.get("code");
        if (val == null) return 0;
        try {
            return val instanceof Number ? ((Number) val).intValue() : Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private void saveErrorResult(TestResult result, List<Map<String, Object>> steps, String errorMessage) {
        result.setActualResult(TestResult.ActualResult.ERROR);
        result.setErrorMessage(errorMessage);
        result.setCompletedAt(Instant.now());
        try {
            result.setStepDetails(objectMapper.writeValueAsString(steps));
        } catch (Exception e) {
            result.setStepDetails("[]");
        }
        testResultRepository.save(result);
    }

    private String extractPersonId(Map<String, Object> response) {
        if (response == null) return null;
        // Look for "personId" key directly
        if (response.containsKey("personId")) {
            Object val = response.get("personId");
            return val != null ? val.toString() : null;
        }
        // Try "person" nested object
        if (response.containsKey("person") && response.get("person") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> person = (Map<String, Object>) response.get("person");
            if (person.containsKey("personId")) {
                Object val = person.get("personId");
                return val != null ? val.toString() : null;
            }
        }
        // Try "id" as fallback
        if (response.containsKey("id")) {
            Object val = response.get("id");
            return val != null ? val.toString() : null;
        }
        return null;
    }

    private String extractAssociationId(Map<String, Object> response) {
        if (response == null) return null;
        if (response.containsKey("associationId")) {
            Object val = response.get("associationId");
            return val != null ? val.toString() : null;
        }
        if (response.containsKey("id")) {
            Object val = response.get("id");
            return val != null ? val.toString() : null;
        }
        return null;
    }

    private HttpHeaders headersWithAuth(String bearerToken, String basicUser, String basicPass) {
        return headersWithAuth(bearerToken, basicUser, basicPass, null);
    }

    private HttpHeaders headersWithAuth(String bearerToken, String basicUser, String basicPass,
                                        String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (basicUser != null && !basicUser.isBlank() && basicPass != null && !basicPass.isBlank()) {
            headers.setBasicAuth(basicUser, basicPass);
        }
        if (correlationId != null) {
            headers.set("X-Correlation-ID", correlationId);
        }
        return headers;
    }

    private Map<String, Object> buildResultData(Result resultConfig) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (resultConfig.getState() != null) data.put("state", resultConfig.getState());
        if (resultConfig.getPass() != null) data.put("pass", resultConfig.getPass());
        if (resultConfig.getComment() != null) data.put("comment", resultConfig.getComment());
        if (resultConfig.getScore() != null) data.put("score", resultConfig.getScore());
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
        if (sent == null || !sent.toString().equals(saved != null ? saved.toString() : null)) {
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("sent", sent);
            diff.put("saved", saved);
            mismatches.put(field, diff);
        }
    }

}
