package dk.dtu.ait.euroteq.autotest.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.dtu.ait.euroteq.autotest.entity.HomeServer;
import dk.dtu.ait.euroteq.autotest.repository.HomeServerRepository;
import dk.dtu.ait.euroteq.autotest.service.AssociationCaptureStore;
import dk.dtu.ait.euroteq.autotest.service.RealTokenStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Transparent OOAPI proxy used during broker-mode enrollment tests.
 *
 * The autotest registers itself as the "home institution" with the service registry for
 * broker flows. The inteken-ontvanger therefore calls these endpoints instead of the real
 * home server. The proxy forwards every call to the real home server and, on a successful
 * POST /associations/external/me, captures the returned associationId so the test runner
 * can use it for the subsequent PATCH/GET verification steps.
 *
 * URL pattern: /ooapi-proxy/{homeServerId}/{sessionId}/...
 *   homeServerId  - ID of the HomeServer entity (identifies the real target server)
 *   sessionId     - UUID generated per broker test run; used as the capture-store key
 */
@RestController
@Slf4j
public class OoapiProxyController {

    private final HomeServerRepository homeServerRepository;
    private final AssociationCaptureStore captureStore;
    private final RealTokenStore realTokenStore;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${euroteq.mock-user.suffix}")
    private String mockUserSuffix;

    public OoapiProxyController(
            HomeServerRepository homeServerRepository,
            AssociationCaptureStore captureStore,
            RealTokenStore realTokenStore,
            @Qualifier("standardRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.homeServerRepository = homeServerRepository;
        this.captureStore = captureStore;
        this.realTokenStore = realTokenStore;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    private String resolveAuthHeader(String authHeader, String sessionId) {
        String realToken = realTokenStore.lookup(sessionId);
        if (realToken != null) {
            log.debug("Proxy: swapping mock token with real token for session {}", sessionId);
            return "Bearer " + realToken;
        }
        return authHeader;
    }

    /**
     * Proxies GET /associations/{associationId} to the real home server.
     * Called by the inteken-ontvanger after a successful POST to fetch the created association state.
     */
    @GetMapping("/ooapi-proxy/{homeServerId}/{sessionId}/associations/{associationId}")
    public ResponseEntity<Map<String, Object>> proxyAssociationGet(
            @PathVariable Long homeServerId,
            @PathVariable String sessionId,
            @PathVariable String associationId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage
    ) {
        String resolvedAuth = resolveAuthHeader(authHeader, sessionId);
        return proxyToHomeServer(homeServerId, HttpMethod.GET,
                "/associations/" + associationId, null, resolvedAuth, acceptLanguage);
    }

    /**
     * Proxies PATCH /associations/{associationId} to the real home server.
     * Called by the inteken-ontvanger when forwarding state/result updates.
     */
    @PatchMapping("/ooapi-proxy/{homeServerId}/{sessionId}/associations/{associationId}")
    public ResponseEntity<Map<String, Object>> proxyAssociationPatch(
            @PathVariable Long homeServerId,
            @PathVariable String sessionId,
            @PathVariable String associationId,
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage
    ) {
        String resolvedAuth = resolveAuthHeader(authHeader, sessionId);
        return proxyToHomeServer(homeServerId, HttpMethod.PATCH,
                "/associations/" + associationId, rawBody, resolvedAuth, acceptLanguage);
    }

    /**
     * Proxies POST /associations/external/me to the real home server.
     * Captures the returned associationId in the store so the test runner can retrieve it.
     */
    @PostMapping("/ooapi-proxy/{homeServerId}/{sessionId}/associations/external/me")
    public ResponseEntity<Map<String, Object>> proxyAssociationCreate(
            @PathVariable Long homeServerId,
            @PathVariable String sessionId,
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage
    ) {
        String resolvedAuth = resolveAuthHeader(authHeader, sessionId);
        HomeServer homeServer = homeServerRepository.findById(homeServerId).orElse(null);
        if (homeServer == null) {
            log.warn("Proxy: homeServer {} not found", homeServerId);
            return ResponseEntity.notFound().build();
        }

        String targetUrl = homeServer.getUrl() + "/associations/external/me";
        String captureKey = homeServerId + ":" + sessionId;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        if (resolvedAuth != null) headers.set(HttpHeaders.AUTHORIZATION, resolvedAuth);
        if (acceptLanguage != null) headers.set("Accept-Language", acceptLanguage);

        log.info("Proxy: forwarding POST /associations/external/me → {} (key={})", targetUrl, captureKey);

        try {
            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>)
                    (ResponseEntity<?>) restTemplate.exchange(
                            targetUrl, HttpMethod.POST,
                            new HttpEntity<>(rawBody, headers),
                            Map.class
                    );

            Map<String, Object> body = response.getBody() != null
                    ? response.getBody() : Collections.emptyMap();

            if (response.getStatusCode().is2xxSuccessful()) {
                Object assocId = body.get("associationId");
                if (assocId != null) {
                    captureStore.capture(captureKey, assocId.toString());
                    log.info("Proxy: captured associationId={} (key={})", assocId, captureKey);
                } else {
                    log.warn("Proxy: 2xx response but no associationId in body (key={})", captureKey);
                }
            }

            return ResponseEntity.status(response.getStatusCode()).body(body);

        } catch (HttpStatusCodeException e) {
            log.warn("Proxy: home server returned {} for {}", e.getStatusCode(), targetUrl);
            Map<String, Object> errorBody;
            try {
                errorBody = objectMapper.readValue(
                        e.getResponseBodyAsString(),
                        new TypeReference<Map<String, Object>>() {}
                );
            } catch (Exception ignored) {
                errorBody = Map.of(
                        "error", e.getStatusCode().value(),
                        "message", e.getResponseBodyAsString()
                );
            }
            return ResponseEntity.status(e.getStatusCode()).body(errorBody);

        } catch (Exception e) {
            log.error("Proxy: error forwarding to {}", targetUrl, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "proxy_error", "message", e.getMessage()));
        }
    }

    /**
     * Proxies GET /persons/me to the home server with token swap and person-id mocks.
     * Called by the enrollment receiver (inteken-ontvanger) during broker-mode /api/start
     * when it retrieves person data via the personURI from the enrollment request.
     * <p>
     * The proxy:
     * <ol>
     *   <li>Resolves the real token from the session (stored during broker setup)</li>
     *   <li>Forwards the request to the real home server with the real token</li>
     *   <li>Transforms the response: replaces personId with a deterministic UUID derived
     *       from the real personId + suffix, and mails with plus-addressed variant</li>
     * </ol>
     */
    @GetMapping("/ooapi-proxy/{homeServerId}/{sessionId}/{testRunId}/persons/me")
    public ResponseEntity<Map<String, Object>> proxyBrokerPersonsMe(
            @PathVariable Long homeServerId,
            @PathVariable String sessionId,
            @PathVariable Long testRunId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage
    ) {
        String resolvedAuth = resolveAuthHeader(authHeader, sessionId);

        HomeServer homeServer = homeServerRepository.findById(homeServerId).orElse(null);
        if (homeServer == null) {
            log.warn("Proxy: homeServer {} not found", homeServerId);
            return ResponseEntity.notFound().build();
        }

        String targetUrl = homeServer.getUrl() + "/persons/me";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        if (resolvedAuth != null) headers.set(HttpHeaders.AUTHORIZATION, resolvedAuth);
        if (acceptLanguage != null) headers.set("Accept-Language", acceptLanguage);

        log.info("Proxy: forwarding GET /persons/me → {} (testRunId {})", targetUrl, testRunId);

        try {
            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>)
                    (ResponseEntity<?>) restTemplate.exchange(
                            targetUrl, HttpMethod.POST,
                            new HttpEntity<>(headers),
                            Map.class
                    );

            Map<String, Object> body = response.getBody() != null
                    ? response.getBody() : Collections.emptyMap();

            if (response.getStatusCode().is2xxSuccessful() && body.containsKey("personId")) {
                String realPersonId = body.get("personId").toString();
                String mockPersonId = UUID.nameUUIDFromBytes(
                        (realPersonId + testRunId).getBytes(StandardCharsets.UTF_8)).toString();

                Map<String, Object> transformed = new LinkedHashMap<>(body);
                transformed.put("personId", mockPersonId);

                if (body.containsKey("email")) {
                    String realEmail = body.get("email").toString();
                    int atIndex = realEmail.indexOf('@');
                    String mockEmail;
                    if (atIndex > 0) {
                        mockEmail = realEmail.substring(0, atIndex) + "+" + mockUserSuffix
                                + realEmail.substring(atIndex);
                    } else {
                        mockEmail = realEmail + "+" + mockUserSuffix;
                    }
                    transformed.put("email", mockEmail);
                }

                log.info("Proxy: transformed personId {} → {} for testRun {}",
                        realPersonId, mockPersonId, testRunId);
                return ResponseEntity.status(response.getStatusCode()).body(transformed);
            }

            return ResponseEntity.status(response.getStatusCode()).body(body);

        } catch (HttpStatusCodeException e) {
            log.warn("Proxy: home server returned {} for POST /persons/me", e.getStatusCode());
            Map<String, Object> errorBody;
            try {
                errorBody = objectMapper.readValue(
                        e.getResponseBodyAsString(),
                        new TypeReference<Map<String, Object>>() {}
                );
            } catch (Exception ignored) {
                errorBody = Map.of("error", e.getStatusCode().value(),
                        "message", e.getResponseBodyAsString());
            }
            return ResponseEntity.status(e.getStatusCode()).body(errorBody);
        } catch (Exception e) {
            log.error("Proxy: error forwarding GET /persons/me for testRun {}", testRunId, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "proxy_error", "message", e.getMessage()));
        }
    }

    /**
     * Proxies GET /persons/{personId} to the home server with token swap.
     * Called by the enrollment receiver when it needs to look up a person by ID.
     * Uses session-based token resolution from the /ooapi-proxy/ URL pattern.
     */
    @GetMapping("/ooapi-proxy/{homeServerId}/{sessionId}/persons/{personId}")
    public ResponseEntity<Map<String, Object>> proxyBrokerPersonsGet(
            @PathVariable Long homeServerId,
            @PathVariable String sessionId,
            @PathVariable String personId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage
    ) {
        String resolvedAuth = resolveAuthHeader(authHeader, sessionId);
        return proxyToHomeServer(homeServerId, HttpMethod.GET,
                "/persons/" + personId, null, resolvedAuth, acceptLanguage);
    }

    private ResponseEntity<Map<String, Object>> proxyToHomeServer(
            Long homeServerId, HttpMethod method, String path,
            String rawBody, String authHeader, String acceptLanguage) {

        HomeServer homeServer = homeServerRepository.findById(homeServerId).orElse(null);
        if (homeServer == null) {
            log.warn("Proxy: homeServer {} not found", homeServerId);
            return ResponseEntity.notFound().build();
        }

        String targetUrl = homeServer.getUrl() + path;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        if (authHeader != null) headers.set(HttpHeaders.AUTHORIZATION, authHeader);
        if (acceptLanguage != null) headers.set("Accept-Language", acceptLanguage);

        log.info("Proxy: forwarding {} {} → {}", method, path, targetUrl);

        try {
            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>)
                    (ResponseEntity<?>) restTemplate.exchange(
                            targetUrl, method,
                            new HttpEntity<>(rawBody, headers),
                            Map.class
                    );

            Map<String, Object> body = response.getBody() != null
                    ? response.getBody() : Collections.emptyMap();
            return ResponseEntity.status(response.getStatusCode()).body(body);

        } catch (HttpStatusCodeException e) {
            log.warn("Proxy: home server returned {} for {} {}", e.getStatusCode(), method, targetUrl);
            Map<String, Object> errorBody;
            try {
                errorBody = objectMapper.readValue(
                        e.getResponseBodyAsString(),
                        new TypeReference<Map<String, Object>>() {}
                );
            } catch (Exception ignored) {
                errorBody = Map.of("error", e.getStatusCode().value(),
                        "message", e.getResponseBodyAsString());
            }
            return ResponseEntity.status(e.getStatusCode()).body(errorBody);

        } catch (Exception e) {
            log.error("Proxy: error forwarding {} {} → {}", method, path, targetUrl, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "proxy_error", "message", e.getMessage()));
        }
    }
}
