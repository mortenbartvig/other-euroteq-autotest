package dk.dtu.ait.euroteq.autotest.config;

import dk.dtu.ait.euroteq.autotest.entity.AppUser;
import dk.dtu.ait.euroteq.autotest.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements ApplicationRunner {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        relaxLegacyNotNullConstraints();
        ensureDefaultAdmin();
    }

    private void relaxLegacyNotNullConstraints() {
        // expected_result was previously NOT NULL; it is now optional.
        // home_server_id and host_server_id are made nullable for simulated test runs.
        // ddl-auto:update does not relax existing constraints, so we do it here.
        relaxColumn("test_results", "expected_result");
        relaxColumn("offerings", "expected_result");
        relaxColumn("test_users", "home_server_id");
        relaxColumn("offerings", "host_server_id");
    }

    private void relaxColumn(String table, String column) {
        try {
            jdbcTemplate.execute(
                "ALTER TABLE " + table + " ALTER " + column + " DROP NOT NULL");
            log.info("Relaxed NOT NULL constraint on {}.{}", table, column);
        } catch (Exception e) {
            log.info("Could not relax {}.{} constraint (already nullable or column absent): {}",
                    table, column, e.getMessage());
        }
    }

    private void ensureDefaultAdmin() {
        long adminCount = appUserRepository.countByRole(AppUser.Role.ADMIN);
        if (adminCount == 0) {
            AppUser admin = new AppUser();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setRole(AppUser.Role.ADMIN);
            admin.setMustChangePassword(true);
            appUserRepository.save(admin);
            log.warn("=============================================================");
            log.warn("Default admin user created with username 'admin' and password 'admin123'.");
            log.warn("PLEASE CHANGE THIS PASSWORD IMMEDIATELY after first login!");
            log.warn("=============================================================");
        }
    }
}
