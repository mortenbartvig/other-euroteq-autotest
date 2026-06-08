package dk.dtu.ait.euroteq.autotest.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Convert(converter = dk.dtu.ait.euroteq.autotest.converter.JsonSetConverter.class)
    @Column(columnDefinition = "TEXT")
    private Set<String> offlineHomeInstitutions = new HashSet<>();

    @Convert(converter = dk.dtu.ait.euroteq.autotest.converter.JsonSetConverter.class)
    @Column(columnDefinition = "TEXT")
    private Set<String> offlineHostInstitutions = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(columnDefinition = "TEXT")
    private String statusMessage;

    @Column(nullable = false)
    private boolean simulated = false;

    @Convert(converter = dk.dtu.ait.euroteq.autotest.converter.JsonMapConverter.class)
    @Column(columnDefinition = "TEXT")
    private Map<String, Long> institutionServerMapping = new HashMap<>();

    @OneToMany(mappedBy = "testRun", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TestResult> results = new ArrayList<>();

    public enum Status {
        PENDING, RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, COMPLETED_WITH_DENIED, FAILED
    }
}
