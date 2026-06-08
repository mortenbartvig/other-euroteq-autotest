package dk.dtu.ait.euroteq.autotest.dto;

import dk.dtu.ait.euroteq.autotest.entity.HostServer;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class HostServerDto {

    private Long id;
    private String name;
    private String url;
    private String enrollmentPath;
    private String enrollmentMode;
    private String brokerScope;
    private Long ownerId;
    private String ownerUsername;
    private String basicAuthUsername;
    private boolean hasBasicAuth;

    public static HostServerDto from(HostServer server) {
        HostServerDto dto = new HostServerDto();
        dto.setId(server.getId());
        dto.setName(server.getName());
        dto.setUrl(server.getUrl());
        dto.setEnrollmentPath(server.getEnrollmentPath());
        dto.setEnrollmentMode(server.getEnrollmentMode() != null ? server.getEnrollmentMode() : "BROKER");
        dto.setBrokerScope(server.getBrokerScope() != null ? server.getBrokerScope() : "offline_access");
        dto.setOwnerId(server.getOwner().getId());
        dto.setOwnerUsername(server.getOwner().getUsername());
        dto.setBasicAuthUsername(server.getBasicAuthUsername());
        dto.setHasBasicAuth(server.getBasicAuthPassword() != null && !server.getBasicAuthPassword().isBlank());
        return dto;
    }
}
