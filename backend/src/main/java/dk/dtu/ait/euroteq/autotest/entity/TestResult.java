package dk.dtu.ait.euroteq.autotest.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "test_results")
@Getter
@Setter
@NoArgsConstructor
public class TestResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_run_id", nullable = false)
    private TestRun testRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_user_id", nullable = false)
    private TestUser testUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offering_id", nullable = false)
    private Offering offering;

    @Column
    private String expectedResult;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActualResult actualResult;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String stepDetails;

    private Instant startedAt;

    private Instant completedAt;

    public enum ActualResult {
        SUCCESS, DENIED, ERROR, SKIPPED
    }
}
