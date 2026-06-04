package dk.dtu.ait.euroteq.autotest.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "offerings")
@Getter
@Setter
@NoArgsConstructor
public class Offering {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String offeringId;

    @Column(columnDefinition = "TEXT")
    private String offeringData;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_server_id", nullable = false)
    private HostServer hostServer;
}
