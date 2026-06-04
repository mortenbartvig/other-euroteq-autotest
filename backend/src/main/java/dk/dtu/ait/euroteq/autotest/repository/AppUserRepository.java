package dk.dtu.ait.euroteq.autotest.repository;

import dk.dtu.ait.euroteq.autotest.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    long countByRole(AppUser.Role role);
}
