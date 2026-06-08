package dk.dtu.ait.euroteq.autotest.entity;

import dk.dtu.ait.euroteq.autotest.converter.AcademicLevelAttributeConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "test_users")
@Getter
@Setter
@NoArgsConstructor
public class TestUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String username;

    @Column(columnDefinition = "TEXT")
    private String claims;

    @Convert(converter = AcademicLevelAttributeConverter.class)
    @Column(columnDefinition = "VARCHAR(32)")
    private AcademicLevel academicLevel;

    @Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE NOT NULL")
    private boolean alwaysDenied = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_server_id", nullable = false)
    private HomeServer homeServer;
}
