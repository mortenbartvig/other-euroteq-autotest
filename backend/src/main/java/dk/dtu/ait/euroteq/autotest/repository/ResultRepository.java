package dk.dtu.ait.euroteq.autotest.repository;

import dk.dtu.ait.euroteq.autotest.entity.HostServer;
import dk.dtu.ait.euroteq.autotest.entity.Result;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResultRepository extends JpaRepository<Result, Long> {

    List<Result> findByHostServer(HostServer hostServer);

    void deleteByHostServer(HostServer hostServer);
}
