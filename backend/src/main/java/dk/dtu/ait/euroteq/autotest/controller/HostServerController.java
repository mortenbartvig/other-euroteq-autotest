package dk.dtu.ait.euroteq.autotest.controller;

import dk.dtu.ait.euroteq.autotest.dto.*;
import dk.dtu.ait.euroteq.autotest.entity.AppUser;
import dk.dtu.ait.euroteq.autotest.entity.HostServer;
import dk.dtu.ait.euroteq.autotest.entity.Offering;
import dk.dtu.ait.euroteq.autotest.entity.Result;
import dk.dtu.ait.euroteq.autotest.repository.AppUserRepository;
import dk.dtu.ait.euroteq.autotest.repository.HostServerRepository;
import dk.dtu.ait.euroteq.autotest.repository.OfferingRepository;
import dk.dtu.ait.euroteq.autotest.repository.ResultRepository;
import dk.dtu.ait.euroteq.autotest.repository.TestResultRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/host-servers")
@RequiredArgsConstructor
@Slf4j
public class HostServerController {

    private final HostServerRepository hostServerRepository;
    private final OfferingRepository offeringRepository;
    private final ResultRepository resultRepository;
    private final TestResultRepository testResultRepository;
    private final AppUserRepository appUserRepository;

    @GetMapping
    public ResponseEntity<List<HostServerDto>> getHostServers() {
        AppUser currentUser = getCurrentUser();
        List<HostServer> servers;

        if (currentUser.getRole() == AppUser.Role.ADMIN) {
            servers = hostServerRepository.findAll();
        } else {
            servers = hostServerRepository.findByOwner(currentUser);
        }

        return ResponseEntity.ok(servers.stream()
                .map(HostServerDto::from)
                .collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<HostServerDto> getHostServer(@PathVariable Long id) {
        AppUser currentUser = getCurrentUser();
        return hostServerRepository.findById(id)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .map(s -> ResponseEntity.ok(HostServerDto.from(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> createHostServer(@Valid @RequestBody HostServerRequest request) {
        AppUser currentUser = getCurrentUser();

        AppUser owner;
        if (request.getOwnerId() != null && currentUser.getRole() == AppUser.Role.ADMIN) {
            owner = appUserRepository.findById(request.getOwnerId()).orElse(currentUser);
        } else {
            owner = currentUser;
        }

        HostServer server = new HostServer();
        server.setName(request.getName());
        server.setUrl(request.getUrl());
        server.setEnrollmentPath(request.getEnrollmentPath() != null
                ? request.getEnrollmentPath()
                : "/persons/{personId}/associations");
        server.setEnrollmentMode(request.getEnrollmentMode() != null ? request.getEnrollmentMode() : "BROKER");
        server.setBrokerScope(request.getBrokerScope() != null ? request.getBrokerScope() : "offline_access");
        server.setBasicAuthUsername(request.getBasicAuthUsername());
        server.setBasicAuthPassword(request.getBasicAuthPassword());
        server.setOffline(request.isOffline());
        server.setOwner(owner);

        HostServer saved = hostServerRepository.save(server);
        log.info("Created host server '{}' for user '{}'", saved.getName(), owner.getUsername());
        return ResponseEntity.status(201).body(HostServerDto.from(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateHostServer(@PathVariable Long id,
                                               @Valid @RequestBody HostServerRequest request) {
        AppUser currentUser = getCurrentUser();
        return hostServerRepository.findById(id)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .map(server -> {
                    server.setName(request.getName());
                    server.setUrl(request.getUrl());
                    if (request.getEnrollmentPath() != null) {
                        server.setEnrollmentPath(request.getEnrollmentPath());
                    }
                    if (request.getEnrollmentMode() != null) {
                        server.setEnrollmentMode(request.getEnrollmentMode());
                    }
                    if (request.getBrokerScope() != null) {
                        server.setBrokerScope(request.getBrokerScope());
                    }
                    server.setBasicAuthUsername(request.getBasicAuthUsername());
                    if (request.getBasicAuthPassword() != null && !request.getBasicAuthPassword().isBlank()) {
                        server.setBasicAuthPassword(request.getBasicAuthPassword());
                    }
                    server.setOffline(request.isOffline());
                    if (request.getOwnerId() != null && currentUser.getRole() == AppUser.Role.ADMIN) {
                        appUserRepository.findById(request.getOwnerId())
                                .ifPresent(server::setOwner);
                    }
                    HostServer saved = hostServerRepository.save(server);
                    return ResponseEntity.ok(HostServerDto.from(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @jakarta.transaction.Transactional
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteHostServer(@PathVariable Long id) {
        AppUser currentUser = getCurrentUser();
        return hostServerRepository.findById(id)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .map(server -> {
                    testResultRepository.deleteByHostServerId(id);
                    hostServerRepository.delete(server);
                    log.info("Deleted host server '{}' (id={})", server.getName(), id);
                    return ResponseEntity.noContent().build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // --- Offerings nested resource ---

    @GetMapping("/{hostServerId}/offerings")
    public ResponseEntity<?> getOfferings(@PathVariable Long hostServerId) {
        AppUser currentUser = getCurrentUser();
        return hostServerRepository.findById(hostServerId)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .map(server -> {
                    List<OfferingDto> offerings = offeringRepository.findByHostServer(server).stream()
                            .map(OfferingDto::from)
                            .collect(Collectors.toList());
                    return ResponseEntity.ok(offerings);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{hostServerId}/offerings/{offeringId}")
    public ResponseEntity<?> getOffering(@PathVariable Long hostServerId, @PathVariable Long offeringId) {
        AppUser currentUser = getCurrentUser();
        return hostServerRepository.findById(hostServerId)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .flatMap(server -> offeringRepository.findById(offeringId)
                        .filter(o -> o.getHostServer().getId().equals(hostServerId)))
                .map(o -> ResponseEntity.ok(OfferingDto.from(o)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{hostServerId}/offerings")
    public ResponseEntity<?> createOffering(@PathVariable Long hostServerId,
                                             @Valid @RequestBody OfferingRequest request) {
        AppUser currentUser = getCurrentUser();
        return hostServerRepository.findById(hostServerId)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .map(server -> {
                    Offering offering = new Offering();
                    offering.setName(request.getName());
                    offering.setOfferingId(request.getOfferingId());
                    offering.setOfferingData(request.getOfferingData());
                    offering.setCourseLevel(request.getCourseLevel());
                    offering.setHostServer(server);
                    Offering saved = offeringRepository.save(offering);
                    log.info("Created offering '{}' for host server '{}'", saved.getName(), server.getName());
                    return ResponseEntity.status(201).body(OfferingDto.from(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{hostServerId}/offerings/{offeringId}")
    public ResponseEntity<?> updateOffering(@PathVariable Long hostServerId,
                                             @PathVariable Long offeringId,
                                             @Valid @RequestBody OfferingRequest request) {
        AppUser currentUser = getCurrentUser();
        return hostServerRepository.findById(hostServerId)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .flatMap(server -> offeringRepository.findById(offeringId)
                        .filter(o -> o.getHostServer().getId().equals(hostServerId))
                        .map(offering -> {
                            offering.setName(request.getName());
                            offering.setOfferingId(request.getOfferingId());
                            offering.setOfferingData(request.getOfferingData());
                            if (request.getCourseLevel() != null) {
                                offering.setCourseLevel(request.getCourseLevel());
                            }
                            Offering saved = offeringRepository.save(offering);
                            return ResponseEntity.ok(OfferingDto.from(saved));
                        }))
                .orElse(ResponseEntity.notFound().build());
    }

    @jakarta.transaction.Transactional
    @DeleteMapping("/{hostServerId}/offerings/{offeringId}")
    public ResponseEntity<?> deleteOffering(@PathVariable Long hostServerId,
                                             @PathVariable Long offeringId) {
        AppUser currentUser = getCurrentUser();
        return hostServerRepository.findById(hostServerId)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .flatMap(server -> offeringRepository.findById(offeringId)
                        .filter(o -> o.getHostServer().getId().equals(hostServerId))
                        .map(offering -> {
                            testResultRepository.deleteByOffering(offering);
                            offeringRepository.delete(offering);
                            return ResponseEntity.noContent().build();
                        }))
                .orElse(ResponseEntity.notFound().build());
    }

    // -------------------------------------------------------------------------
    // Results
    // -------------------------------------------------------------------------

    @GetMapping("/{hostServerId}/results")
    public ResponseEntity<?> listResults(@PathVariable Long hostServerId) {
        AppUser currentUser = getCurrentUser();
        return hostServerRepository.findById(hostServerId)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .map(server -> {
                    List<ResultDto> results = resultRepository.findByHostServer(server)
                            .stream().map(ResultDto::from).collect(Collectors.toList());
                    return ResponseEntity.ok(results);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{hostServerId}/results")
    public ResponseEntity<?> createResult(@PathVariable Long hostServerId,
                                           @Valid @RequestBody ResultRequest request) {
        AppUser currentUser = getCurrentUser();
        return hostServerRepository.findById(hostServerId)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .map(server -> {
                    Result result = new Result();
                    applyResultRequest(result, request);
                    result.setHostServer(server);
                    Result saved = resultRepository.save(result);
                    log.info("Created result '{}' for host server '{}'", saved.getName(), server.getName());
                    return ResponseEntity.status(201).body(ResultDto.from(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{hostServerId}/results/{resultId}")
    public ResponseEntity<?> updateResult(@PathVariable Long hostServerId,
                                           @PathVariable Long resultId,
                                           @Valid @RequestBody ResultRequest request) {
        AppUser currentUser = getCurrentUser();
        return hostServerRepository.findById(hostServerId)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .flatMap(server -> resultRepository.findById(resultId)
                        .filter(r -> r.getHostServer().getId().equals(hostServerId))
                        .map(result -> {
                            applyResultRequest(result, request);
                            Result saved = resultRepository.save(result);
                            return ResponseEntity.ok(ResultDto.from(saved));
                        }))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{hostServerId}/results/{resultId}")
    public ResponseEntity<?> deleteResult(@PathVariable Long hostServerId,
                                           @PathVariable Long resultId) {
        AppUser currentUser = getCurrentUser();
        return hostServerRepository.findById(hostServerId)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .flatMap(server -> resultRepository.findById(resultId)
                        .filter(r -> r.getHostServer().getId().equals(hostServerId))
                        .map(result -> {
                            resultRepository.delete(result);
                            log.info("Deleted result '{}' (id={}) from host server '{}'",
                                    result.getName(), resultId, server.getName());
                            return ResponseEntity.noContent().build();
                        }))
                .orElse(ResponseEntity.notFound().build());
    }

    private void applyResultRequest(Result result, ResultRequest request) {
        result.setName(request.getName());
        result.setState(request.getState());
        result.setPass(request.getPass());
        result.setComment(request.getComment());
        result.setScore(request.getScore());
        result.setResultDate(request.getResultDate());
        result.setExt(request.getExt());
        result.setStudyLoad(request.getStudyLoad());
    }

    private AppUser getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return appUserRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new RuntimeException("Current user not found"));
    }

    private boolean canAccess(AppUser currentUser, AppUser resourceOwner) {
        return currentUser.getRole() == AppUser.Role.ADMIN
                || currentUser.getId().equals(resourceOwner.getId());
    }
}
