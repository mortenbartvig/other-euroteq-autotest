package dk.dtu.ait.euroteq.autotest.repository;

import dk.dtu.ait.euroteq.autotest.entity.SimulationInstitution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SimulationInstitutionRepository extends JpaRepository<SimulationInstitution, Long> {
    List<SimulationInstitution> findByConfigId(Long configId);
}
