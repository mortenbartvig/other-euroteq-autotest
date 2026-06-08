package dk.dtu.ait.euroteq.autotest.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "simulation_configs")
@Getter
@Setter
@NoArgsConstructor
public class SimulationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "global_users_per_inst")
    private Integer globalUsersPerInst = 5;

    @Column(name = "global_offerings_per_inst")
    private Integer globalOfferingsPerInst = 3;

 @Column(name = "global_pass_rate")
    private Integer globalPassRate = 80;

    @Column(name = "slow_as_percent")
    private Boolean slowAsPercent = true;

    @Column(name = "slow_percent")
    private Double slowPercent = 10.0;

    @Column(name = "slow_count")
    private Integer slowCount = 5;

    @Column(name = "normal_duration_min")
    private Double normalDurationMin = 1.0;

    @Column(name = "normal_duration_max")
    private Double normalDurationMax = 2.0;

    @Column(name = "slow_duration_min")
    private Double slowDurationMin = 15.0;

    @Column(name = "slow_duration_max")
    private Double slowDurationMax = 25.0;

    @OneToMany(mappedBy = "config", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<SimulationInstitution> institutions = new java.util.ArrayList<>();
}
