package dk.dtu.ait.euroteq.autotest.repository;

import dk.dtu.ait.euroteq.autotest.entity.HostServer;
import dk.dtu.ait.euroteq.autotest.entity.Offering;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OfferingRepository extends JpaRepository<Offering, Long> {

    List<Offering> findByHostServer(HostServer hostServer);

    List<Offering> findByHostServer_Id(Long hostServerId);

    void deleteByHostServer(HostServer hostServer);

    @Query("SELECT o FROM Offering o JOIN FETCH o.hostServer WHERE o.id = :id")
    Optional<Offering> findByIdWithHostServer(@Param("id") Long id);

    @Query("SELECT o FROM Offering o JOIN FETCH o.hostServer")
    List<Offering> findAllWithHostServer();

    Optional<Offering> findByOfferingId(String offeringId);
}
