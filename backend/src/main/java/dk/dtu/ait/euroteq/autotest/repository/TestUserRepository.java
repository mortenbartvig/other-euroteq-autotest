package dk.dtu.ait.euroteq.autotest.repository;

import dk.dtu.ait.euroteq.autotest.entity.HomeServer;
import dk.dtu.ait.euroteq.autotest.entity.TestUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TestUserRepository extends JpaRepository<TestUser, Long> {

    List<TestUser> findByHomeServer(HomeServer homeServer);

    List<TestUser> findByHomeServer_Id(Long homeServerId);

    void deleteByHomeServer(HomeServer homeServer);

    @Query("SELECT u FROM TestUser u JOIN FETCH u.homeServer WHERE u.id = :id")
    Optional<TestUser> findByIdWithHomeServer(@Param("id") Long id);

    @Query("SELECT u FROM TestUser u JOIN FETCH u.homeServer")
    List<TestUser> findAllWithHomeServer();

    Optional<TestUser> findByUsername(String username);
}
