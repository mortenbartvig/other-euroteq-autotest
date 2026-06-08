package dk.dtu.ait.euroteq.autotest.repository;

import dk.dtu.ait.euroteq.autotest.entity.SimulationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SimulationConfigRepository extends JpaRepository<SimulationConfig, Long> {

    @Query("SELECT DISTINCT c FROM SimulationConfig c LEFT JOIN FETCH c.institutions WHERE c.userId = :userId")
    Optional<SimulationConfig> findByUserIdWithInstitutions(@Param("userId") Long userId);

    Optional<SimulationConfig> findByUserId(Long userId);
}
