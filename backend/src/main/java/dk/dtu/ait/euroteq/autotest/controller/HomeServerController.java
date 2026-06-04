package dk.dtu.ait.euroteq.autotest.controller;

import dk.dtu.ait.euroteq.autotest.dto.*;
import dk.dtu.ait.euroteq.autotest.entity.AppUser;
import dk.dtu.ait.euroteq.autotest.entity.HomeServer;
import dk.dtu.ait.euroteq.autotest.entity.TestUser;
import dk.dtu.ait.euroteq.autotest.repository.AppUserRepository;
import dk.dtu.ait.euroteq.autotest.repository.HomeServerRepository;
import dk.dtu.ait.euroteq.autotest.repository.TestResultRepository;
import dk.dtu.ait.euroteq.autotest.repository.TestUserRepository;
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
@RequestMapping("/api/home-servers")
@RequiredArgsConstructor
@Slf4j
public class HomeServerController {

    private final HomeServerRepository homeServerRepository;
    private final TestUserRepository testUserRepository;
    private final TestResultRepository testResultRepository;
    private final AppUserRepository appUserRepository;

    @GetMapping
    public ResponseEntity<List<HomeServerDto>> getHomeServers() {
        AppUser currentUser = getCurrentUser();
        List<HomeServer> servers;

        if (currentUser.getRole() == AppUser.Role.ADMIN) {
            servers = homeServerRepository.findAll();
        } else {
            servers = homeServerRepository.findByOwner(currentUser);
        }

        return ResponseEntity.ok(servers.stream()
                .map(HomeServerDto::from)
                .collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<HomeServerDto> getHomeServer(@PathVariable Long id) {
        AppUser currentUser = getCurrentUser();
        return homeServerRepository.findById(id)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .map(s -> ResponseEntity.ok(HomeServerDto.from(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> createHomeServer(@Valid @RequestBody HomeServerRequest request) {
        AppUser currentUser = getCurrentUser();

        AppUser owner;
        if (request.getOwnerId() != null && currentUser.getRole() == AppUser.Role.ADMIN) {
            owner = appUserRepository.findById(request.getOwnerId())
                    .orElse(currentUser);
        } else {
            owner = currentUser;
        }

        HomeServer server = new HomeServer();
        server.setName(request.getName());
        server.setUrl(request.getUrl());
        server.setBasicAuthUsername(request.getBasicAuthUsername());
        server.setBasicAuthPassword(request.getBasicAuthPassword());
        server.setOwner(owner);

        HomeServer saved = homeServerRepository.save(server);
        log.info("Created home server '{}' for user '{}'", saved.getName(), owner.getUsername());
        return ResponseEntity.status(201).body(HomeServerDto.from(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateHomeServer(@PathVariable Long id,
                                               @Valid @RequestBody HomeServerRequest request) {
        AppUser currentUser = getCurrentUser();
        return homeServerRepository.findById(id)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .map(server -> {
                    server.setName(request.getName());
                    server.setUrl(request.getUrl());
                    server.setBasicAuthUsername(request.getBasicAuthUsername());
                    if (request.getBasicAuthPassword() != null && !request.getBasicAuthPassword().isBlank()) {
                        server.setBasicAuthPassword(request.getBasicAuthPassword());
                    }
                    if (request.getOwnerId() != null && currentUser.getRole() == AppUser.Role.ADMIN) {
                        appUserRepository.findById(request.getOwnerId())
                                .ifPresent(server::setOwner);
                    }
                    HomeServer saved = homeServerRepository.save(server);
                    return ResponseEntity.ok(HomeServerDto.from(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @jakarta.transaction.Transactional
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteHomeServer(@PathVariable Long id) {
        AppUser currentUser = getCurrentUser();
        return homeServerRepository.findById(id)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .map(server -> {
                    testResultRepository.deleteByHomeServer(server);
                    homeServerRepository.delete(server);
                    log.info("Deleted home server '{}' (id={})", server.getName(), id);
                    return ResponseEntity.noContent().build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // --- Test Users nested resource ---

    @GetMapping("/{homeServerId}/test-users")
    public ResponseEntity<?> getTestUsers(@PathVariable Long homeServerId) {
        AppUser currentUser = getCurrentUser();
        return homeServerRepository.findById(homeServerId)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .map(server -> {
                    List<TestUserDto> users = testUserRepository.findByHomeServer(server).stream()
                            .map(TestUserDto::from)
                            .collect(Collectors.toList());
                    return ResponseEntity.ok(users);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{homeServerId}/test-users/{userId}")
    public ResponseEntity<?> getTestUser(@PathVariable Long homeServerId, @PathVariable Long userId) {
        AppUser currentUser = getCurrentUser();
        return homeServerRepository.findById(homeServerId)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .flatMap(server -> testUserRepository.findById(userId)
                        .filter(u -> u.getHomeServer().getId().equals(homeServerId)))
                .map(u -> ResponseEntity.ok(TestUserDto.from(u)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{homeServerId}/test-users")
    public ResponseEntity<?> createTestUser(@PathVariable Long homeServerId,
                                             @Valid @RequestBody TestUserRequest request) {
        AppUser currentUser = getCurrentUser();
        return homeServerRepository.findById(homeServerId)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .map(server -> {
                    TestUser user = new TestUser();
                    user.setName(request.getName());
                    user.setUsername(request.getUsername());
                    user.setClaims(request.getClaims());
                    user.setAcademicLevel(request.getAcademicLevel());
                    user.setAlwaysDenied(request.isAlwaysDenied());
                    user.setHomeServer(server);
                    TestUser saved = testUserRepository.save(user);
                    log.info("Created test user '{}' for home server '{}'", saved.getName(), server.getName());
                    return ResponseEntity.status(201).body(TestUserDto.from(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{homeServerId}/test-users/{userId}")
    public ResponseEntity<?> updateTestUser(@PathVariable Long homeServerId,
                                             @PathVariable Long userId,
                                             @Valid @RequestBody TestUserRequest request) {
        AppUser currentUser = getCurrentUser();
        return homeServerRepository.findById(homeServerId)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .flatMap(server -> testUserRepository.findById(userId)
                        .filter(u -> u.getHomeServer().getId().equals(homeServerId))
                        .map(user -> {
                            user.setName(request.getName());
                            user.setUsername(request.getUsername());
                            user.setClaims(request.getClaims());
                            user.setAcademicLevel(request.getAcademicLevel());
                            user.setAlwaysDenied(request.isAlwaysDenied());
                            TestUser saved = testUserRepository.save(user);
                            return ResponseEntity.ok(TestUserDto.from(saved));
                        }))
                .orElse(ResponseEntity.notFound().build());
    }

    @jakarta.transaction.Transactional
    @DeleteMapping("/{homeServerId}/test-users/{userId}")
    public ResponseEntity<?> deleteTestUser(@PathVariable Long homeServerId,
                                             @PathVariable Long userId) {
        AppUser currentUser = getCurrentUser();
        return homeServerRepository.findById(homeServerId)
                .filter(s -> canAccess(currentUser, s.getOwner()))
                .flatMap(server -> testUserRepository.findById(userId)
                        .filter(u -> u.getHomeServer().getId().equals(homeServerId))
                        .map(user -> {
                            testResultRepository.deleteByTestUser(user);
                            testUserRepository.delete(user);
                            return ResponseEntity.noContent().build();
                        }))
                .orElse(ResponseEntity.notFound().build());
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
