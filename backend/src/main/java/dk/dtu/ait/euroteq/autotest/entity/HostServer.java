package dk.dtu.ait.euroteq.autotest.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "host_servers")
@Getter
@Setter
@NoArgsConstructor
public class HostServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String enrollmentPath = "/persons/{personId}/associations";

    // "DIRECT" = POST directly to enrollmentPath; "BROKER" = full inteken-ontvanger broker flow
    @Column
    private String enrollmentMode = "DIRECT";

    // Extra OAuth scope for BROKER mode (inteken-ontvanger prepends "openid " to this).
    // Use space-separated scopes, e.g. "offline_access email dtu.dk/persons".
    @Column
    private String brokerScope = "offline_access";

    @Column
    private String basicAuthUsername;

    @Column
    private String basicAuthPassword;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @OneToMany(mappedBy = "hostServer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Offering> offerings = new ArrayList<>();

    @OneToMany(mappedBy = "hostServer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Result> results = new ArrayList<>();
}
