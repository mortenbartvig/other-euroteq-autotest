package dk.dtu.ait.euroteq.autotest.repository;

import dk.dtu.ait.euroteq.autotest.entity.TestRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TestRunRepository extends JpaRepository<TestRun, Long> {

    List<TestRun> findAllByOrderByStartedAtDesc();

    Optional<TestRun> findTopByOrderByStartedAtDesc();

    List<TestRun> findByStatus(TestRun.Status status);
}
