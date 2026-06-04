package dk.dtu.ait.euroteq.autotest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.dtu.ait.euroteq.autotest.entity.TestUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class TokenService {

    private final RestTemplate noRedirectRestTemplate;
    private final ObjectMapper objectMapper;

    @Value("${euroteq.mock-oauth-url}")
    private String mockOauthUrl;

    @Value("${euroteq.mock-user.suffix:}")
    private String mockUserSuffix;

    public TokenService(@Qualifier("noRedirectRestTemplate") RestTemplate noRedirectRestTemplate,
                        ObjectMapper objectMapper) {
        this.noRedirectRestTemplate = noRedirectRestTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Generate an OAuth access token for the given test user using the mock OAuth server.
     * Step 1: POST to /myacademicid/authorize to get a redirect with an auth code.
     * Step 2: POST to /myacademicid/token with the code to get the access token.
     */
    public String getAccessToken(TestUser testUser, String mockOauthUrlOverride) throws Exception {
        return getAccessToken(testUser.getUsername(), testUser.getClaims(), mockOauthUrlOverride);
    }

    /**
     * Generate a mock access token for use with the enrollment receiver.
     * Applies the configured mock suffix to the username and transforms identity claims.
     */
    public String getMockAccessToken(TestUser testUser, String mockOauthUrlOverride) throws Exception {
        if (mockUserSuffix == null || mockUserSuffix.isBlank()) {
            return getAccessToken(testUser, mockOauthUrlOverride);
        }
        return getAccessToken(getMockUsername(testUser), getMockClaims(testUser), mockOauthUrlOverride);
    }

    public String getMockUsername(TestUser testUser) {
        return (mockUserSuffix == null || mockUserSuffix.isBlank())
                ? testUser.getUsername()
                : testUser.getUsername() + mockUserSuffix;
    }

    public String getMockClaims(TestUser testUser) {
        return (mockUserSuffix == null || mockUserSuffix.isBlank())
                ? (testUser.getClaims() != null ? testUser.getClaims() : "{}")
                : transformClaims(testUser.getClaims(), mockUserSuffix);
    }

    private String getAccessToken(String username, String claimsJson, String mockOauthUrlOverride) throws Exception {
        String baseUrl = (mockOauthUrlOverride != null && !mockOauthUrlOverride.isBlank())
                ? mockOauthUrlOverride
                : mockOauthUrl;

        String claims = claimsJson != null ? claimsJson : "{}";

        // Step 1: POST to authorize endpoint
        String authorizeUrl = baseUrl + "/myacademicid/authorize"
                + "?response_type=code"
                + "&client_id=test-client"
                + "&redirect_uri=" + URLEncoder.encode("http://localhost/callback", StandardCharsets.UTF_8)
                + "&scope=openid"
                + "&state=teststate";

        HttpHeaders step1Headers = new HttpHeaders();
        step1Headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> step1Body = new LinkedMultiValueMap<>();
        step1Body.add("username", username);
        step1Body.add("claims", claims);

        HttpEntity<MultiValueMap<String, String>> step1Request = new HttpEntity<>(step1Body, step1Headers);

        log.debug("Step 1: Posting to authorize endpoint for user: {}", username);

        ResponseEntity<String> step1Response;
        try {
            step1Response = noRedirectRestTemplate.postForEntity(authorizeUrl, step1Request, String.class);
        } catch (HttpStatusCodeException e) {
            // 302 is thrown as exception by some RestTemplate configurations
            if (e.getStatusCode() == HttpStatus.FOUND || e.getStatusCode().value() == 302) {
                String location = e.getResponseHeaders() != null
                        ? e.getResponseHeaders().getFirst(HttpHeaders.LOCATION)
                        : null;
                if (location == null) {
                    throw new RuntimeException("No Location header in 302 response from authorize endpoint");
                }
                return exchangeCodeForToken(location, baseUrl);
            }
            throw new RuntimeException("Authorize step failed: HTTP " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
        }

        if (step1Response.getStatusCode() == HttpStatus.FOUND || step1Response.getStatusCode().value() == 302) {
            String location = step1Response.getHeaders().getFirst(HttpHeaders.LOCATION);
            if (location == null) {
                throw new RuntimeException("No Location header in 302 response from authorize endpoint");
            }
            return exchangeCodeForToken(location, baseUrl);
        } else {
            throw new RuntimeException("Expected 302 redirect from authorize endpoint, got: " + step1Response.getStatusCode());
        }
    }

    private String exchangeCodeForToken(String location, String baseUrl) throws Exception {
        // Extract code from Location header (e.g. http://localhost/callback?code=XYZ&state=teststate)
        URI locationUri = new URI(location);
        String query = locationUri.getQuery();
        if (query == null) {
            throw new RuntimeException("No query parameters in redirect Location: " + location);
        }

        String code = null;
        for (String param : query.split("&")) {
            if (param.startsWith("code=")) {
                code = param.substring("code=".length());
                break;
            }
        }

        if (code == null) {
            throw new RuntimeException("No 'code' parameter found in redirect Location: " + location);
        }

        // Step 2: Exchange code for token
        String tokenUrl = baseUrl + "/myacademicid/token";

        HttpHeaders step2Headers = new HttpHeaders();
        step2Headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> step2Body = new LinkedMultiValueMap<>();
        step2Body.add("grant_type", "authorization_code");
        step2Body.add("code", code);
        step2Body.add("client_id", "test-client");
        step2Body.add("client_secret", "test-secret");
        step2Body.add("redirect_uri", "http://localhost/callback");

        HttpEntity<MultiValueMap<String, String>> step2Request = new HttpEntity<>(step2Body, step2Headers);

        log.debug("Step 2: Exchanging code for token at: {}", tokenUrl);

        ResponseEntity<Map> step2Response = noRedirectRestTemplate.postForEntity(tokenUrl, step2Request, Map.class);

        if (!step2Response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Token endpoint returned: " + step2Response.getStatusCode());
        }

        Map<String, Object> tokenBody = step2Response.getBody();
        if (tokenBody == null || !tokenBody.containsKey("access_token")) {
            throw new RuntimeException("No access_token in token response");
        }

        return (String) tokenBody.get("access_token");
    }

    public String getAccessToken(TestUser testUser) throws Exception {
        return getAccessToken(testUser, null);
    }

    private String transformClaims(String claimsJson, String suffix) {
        if (claimsJson == null || claimsJson.isBlank()) return "{}";
        try {
            Map<String, Object> claims = objectMapper.readValue(claimsJson, new TypeReference<>() {});
            if (claims.containsKey("email")) {
                String email = claims.get("email").toString();
                int atIndex = email.indexOf('@');
                if (atIndex > 0) {
                    claims.put("email", email.substring(0, atIndex) + "+" + suffix + email.substring(atIndex));
                } else {
                    claims.put("email", email + "+" + suffix);
                }
            }
            return objectMapper.writeValueAsString(claims);
        } catch (Exception e) {
            log.warn("Failed to transform claims for mock user", e);
            return claimsJson;
        }
    }
}
