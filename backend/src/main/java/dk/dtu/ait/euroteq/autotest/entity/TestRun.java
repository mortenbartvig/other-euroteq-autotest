package dk.dtu.ait.euroteq.autotest.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "test_runs")
@Getter
@Setter
@NoArgsConstructor
public class TestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant completedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "started_by_id", nullable = false)
    private AppUser startedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(columnDefinition = "TEXT")
    private String statusMessage;

    @OneToMany(mappedBy = "testRun", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TestResult> results = new ArrayList<>();

    public enum Status {
        PENDING, RUNNING, COMPLETED, FAILED
    }
}
