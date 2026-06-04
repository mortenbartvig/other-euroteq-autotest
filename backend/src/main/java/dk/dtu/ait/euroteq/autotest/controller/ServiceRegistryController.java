package dk.dtu.ait.euroteq.autotest.controller;

import dk.dtu.ait.euroteq.autotest.repository.HomeServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/service-registry")
@RequiredArgsConstructor
@Slf4j
public class ServiceRegistryController {

    private final HomeServerRepository homeServerRepository;

    @Value("${euroteq.base-url}")
    private String autotestBaseUrl;

    @PostMapping("/api/validate-service-registry-endpoints")
    public ResponseEntity<Map<String, Boolean>> validate(@RequestBody Map<String, Object> request) {
        String homeInstitution = (String) request.get("homeInstitution");
        boolean valid = homeInstitution != null && (
                homeServerRepository.findAll().stream()
                        .anyMatch(s -> homeInstitution.equals(s.getUrl())
                                || homeInstitution.startsWith(s.getUrl() + "/"))
                || homeInstitution.startsWith(autotestBaseUrl + "/ooapi-proxy/")
        );
        log.debug("Service registry validate: homeInstitution={}, valid={}", homeInstitution, valid);
        return ResponseEntity.ok(Map.of("valid", valid));
    }

    @PostMapping("/api/associations-uri")
    public ResponseEntity<Map<String, String>> associationsUri(@RequestBody Map<String, String> request) {
        String homeInstitution = request.get("homeInstitution");
        String associationsURI = homeInstitution != null ? homeInstitution + "/associations" : "";
        log.debug("Service registry associations-uri: {}", associationsURI);
        return ResponseEntity.ok(Map.of("associationsURI", associationsURI));
    }

    @PostMapping("/api/persons-uri")
    public ResponseEntity<Map<String, String>> personsUri(@RequestBody Map<String, String> request) {
        String homeInstitution = request.get("homeInstitution");
        String personsURI = homeInstitution != null ? homeInstitution + "/persons/me" : "";
        log.debug("Service registry persons-uri: {}", personsURI);
        return ResponseEntity.ok(Map.of("personsURI", personsURI));
    }
}
