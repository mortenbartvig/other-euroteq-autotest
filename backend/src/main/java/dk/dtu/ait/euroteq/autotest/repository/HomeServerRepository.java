package dk.dtu.ait.euroteq.autotest.repository;

import dk.dtu.ait.euroteq.autotest.entity.AppUser;
import dk.dtu.ait.euroteq.autotest.entity.HomeServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HomeServerRepository extends JpaRepository<HomeServer, Long> {

    List<HomeServer> findByOwner(AppUser owner);

    List<HomeServer> findByOwner_Id(Long ownerId);

    boolean existsByIdAndOwner(Long id, AppUser owner);
}
