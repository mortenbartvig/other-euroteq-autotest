package dk.dtu.ait.euroteq.autotest.repository;

import dk.dtu.ait.euroteq.autotest.entity.HomeServer;
import dk.dtu.ait.euroteq.autotest.entity.Offering;
import dk.dtu.ait.euroteq.autotest.entity.TestResult;
import dk.dtu.ait.euroteq.autotest.entity.TestRun;
import dk.dtu.ait.euroteq.autotest.entity.TestUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestResultRepository extends JpaRepository<TestResult, Long> {

    List<TestResult> findByTestRun(TestRun testRun);

    List<TestResult> findByTestRun_Id(Long testRunId);

    @Query("SELECT tr FROM TestResult tr " +
           "JOIN FETCH tr.testUser tu " +
           "JOIN FETCH tu.homeServer hs " +
           "JOIN FETCH tr.offering o " +
           "JOIN FETCH o.hostServer host " +
           "WHERE tr.testRun.id = :testRunId")
    List<TestResult> findByTestRunIdWithDetails(@Param("testRunId") Long testRunId);

    @Query("SELECT tr FROM TestResult tr " +
           "JOIN FETCH tr.testUser tu " +
           "JOIN FETCH tu.homeServer hs " +
           "JOIN FETCH tr.offering o " +
           "JOIN FETCH o.hostServer host " +
           "WHERE tr.testRun.id = :testRunId " +
           "AND hs.id = :homeServerId " +
           "AND host.id = :hostServerId")
    List<TestResult> findByTestRunIdAndServers(
            @Param("testRunId") Long testRunId,
            @Param("homeServerId") Long homeServerId,
            @Param("hostServerId") Long hostServerId);

    @Modifying
    @Query("DELETE FROM TestResult tr WHERE tr.testUser = :testUser")
    void deleteByTestUser(@Param("testUser") TestUser testUser);

    @Modifying
    @Query("DELETE FROM TestResult tr WHERE tr.testUser.homeServer = :homeServer")
    void deleteByHomeServer(@Param("homeServer") HomeServer homeServer);

    @Modifying
    @Query("DELETE FROM TestResult tr WHERE tr.offering = :offering")
    void deleteByOffering(@Param("offering") Offering offering);

    @Modifying
    @Query("DELETE FROM TestResult tr WHERE tr.offering.hostServer.id = :hostServerId")
    void deleteByHostServerId(@Param("hostServerId") Long hostServerId);

    @Query("SELECT tr FROM TestResult tr " +
           "JOIN FETCH tr.testUser tu " +
           "JOIN FETCH tu.homeServer hs " +
           "JOIN FETCH tr.offering o " +
           "JOIN FETCH o.hostServer host " +
           "WHERE tr.testRun.id IN :runIds")
    List<TestResult> findByTestRunIdsWithDetails(@Param("runIds") List<Long> runIds);
}
