package dk.dtu.ait.euroteq.autotest.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "host_results")
@Getter
@Setter
@NoArgsConstructor
public class Result {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column
    private String state;

    @Column
    private String pass;

    @Column
    private String comment;

    @Column
    private String score;

    @Column
    private String resultDate;

    @Column(columnDefinition = "TEXT")
    private String ext;

    @Column(columnDefinition = "TEXT")
    private String studyLoad;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_server_id", nullable = false)
    private HostServer hostServer;
}
