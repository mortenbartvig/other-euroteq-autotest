package dk.dtu.ait.euroteq.autotest.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "home_servers")
@Getter
@Setter
@NoArgsConstructor
public class HomeServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String url;

    @Column
    private String basicAuthUsername;

    @Column
    private String basicAuthPassword;

    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean offline = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @OneToMany(mappedBy = "homeServer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TestUser> testUsers = new ArrayList<>();
}
