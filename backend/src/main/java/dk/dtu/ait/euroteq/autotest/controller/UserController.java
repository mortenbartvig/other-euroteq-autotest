package dk.dtu.ait.euroteq.autotest.controller;

import dk.dtu.ait.euroteq.autotest.dto.*;
import dk.dtu.ait.euroteq.autotest.entity.AppUser;
import dk.dtu.ait.euroteq.autotest.repository.AppUserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<AppUserDto>> getAllUsers() {
        List<AppUserDto> users = appUserRepository.findAll().stream()
                .map(AppUserDto::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AppUserDto> getUser(@PathVariable Long id) {
        return appUserRepository.findById(id)
                .map(user -> ResponseEntity.ok(AppUserDto.from(user)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> createUser(@Valid @RequestBody CreateUserRequest request) {
        if (appUserRepository.existsByUsername(request.getUsername())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username already exists"));
        }

        AppUser user = new AppUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(request.getRole());
        user.setMustChangePassword(true);

        AppUser saved = appUserRepository.save(user);
        log.info("Created user '{}' with role {}", saved.getUsername(), saved.getRole());
        return ResponseEntity.status(201).body(AppUserDto.from(saved));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateUser(@PathVariable Long id,
                                         @RequestBody UpdateUserRequest request) {
        return appUserRepository.findById(id)
                .map(user -> {
                    if (request.getRole() != null) {
                        user.setRole(request.getRole());
                    }
                    AppUser saved = appUserRepository.save(user);
                    return ResponseEntity.ok(AppUserDto.from(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteUser(@PathVariable Long id) {
        if (!appUserRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        appUserRepository.deleteById(id);
        log.info("Deleted user with id {}", id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> resetPassword(@PathVariable Long id,
                                            @Valid @RequestBody ResetPasswordRequest request) {
        return appUserRepository.findById(id)
                .map(user -> {
                    user.setPassword(passwordEncoder.encode(request.getNewPassword()));
                    user.setMustChangePassword(true);
                    appUserRepository.save(user);
                    log.info("Reset password for user '{}'", user.getUsername());
                    return ResponseEntity.ok(Map.of("message", "Password reset successfully"));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
