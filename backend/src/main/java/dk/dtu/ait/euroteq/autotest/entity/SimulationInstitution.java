package dk.dtu.ait.euroteq.autotest.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "simulation_institutions")
@Getter
@Setter
@NoArgsConstructor
public class SimulationInstitution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "config_id", nullable = false)
    private SimulationConfig config;

    @Column(name = "users_override")
    private Integer usersOverride;

    @Column(name = "offerings_override")
    private Integer offeringsOverride;

    @Column(name = "pass_rate_override")
    private Integer passRateOverride;

    @Column(name = "use_global_pass_rate")
    private Boolean useGlobalPassRate = true;

    @Column(name = "home_server_offline")
    private boolean homeServerOffline;

    @Column(name = "host_server_offline")
    private boolean hostServerOffline;
}
