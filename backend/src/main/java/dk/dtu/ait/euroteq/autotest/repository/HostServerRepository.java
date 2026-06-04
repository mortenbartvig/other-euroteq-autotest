package dk.dtu.ait.euroteq.autotest.repository;

import dk.dtu.ait.euroteq.autotest.entity.AppUser;
import dk.dtu.ait.euroteq.autotest.entity.HostServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HostServerRepository extends JpaRepository<HostServer, Long> {

    List<HostServer> findByOwner(AppUser owner);

    List<HostServer> findByOwner_Id(Long ownerId);

    boolean existsByIdAndOwner(Long id, AppUser owner);
}
