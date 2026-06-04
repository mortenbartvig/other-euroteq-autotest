package dk.dtu.ait.euroteq.autotest.controller;

import dk.dtu.ait.euroteq.autotest.dto.AppUserDto;
import dk.dtu.ait.euroteq.autotest.dto.ChangePasswordRequest;
import dk.dtu.ait.euroteq.autotest.dto.LoginRequest;
import dk.dtu.ait.euroteq.autotest.entity.AppUser;
import dk.dtu.ait.euroteq.autotest.repository.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpRequest) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);

            HttpSession session = httpRequest.getSession(true);
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

            AppUser user = appUserRepository.findByUsername(request.getUsername())
                    .orElseThrow();

            log.info("User '{}' logged in", request.getUsername());
            return ResponseEntity.ok(AppUserDto.from(user));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
        } catch (Exception e) {
            log.error("Login error", e);
            return ResponseEntity.status(500).body(Map.of("error", "Authentication failed"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getPrincipal().equals("anonymousUser")) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        String username = authentication.getName();
        return appUserRepository.findByUsername(username)
                .map(user -> ResponseEntity.ok(AppUserDto.from(user)))
                .orElse(ResponseEntity.status(404).body(null));
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                             HttpServletRequest httpRequest) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        String username = authentication.getName();
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            return ResponseEntity.status(400).body(Map.of("error", "Current password is incorrect"));
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setMustChangePassword(false);
        appUserRepository.save(user);

        log.info("User '{}' changed their password", username);
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }
}
