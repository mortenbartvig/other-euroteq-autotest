package dk.dtu.ait.euroteq.autotest.entity;

import dk.dtu.ait.euroteq.autotest.converter.AcademicLevelAttributeConverter;
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

    @Convert(converter = AcademicLevelAttributeConverter.class)
    @Column(columnDefinition = "VARCHAR(32)")
    private AcademicLevel courseLevel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_server_id", nullable = false)
    private HostServer hostServer;
}
