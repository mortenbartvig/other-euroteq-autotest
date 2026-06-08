package dk.dtu.ait.euroteq.autotest.dto;

import dk.dtu.ait.euroteq.autotest.entity.HomeServer;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class HomeServerDto {

    private Long id;
    private String name;
    private String url;
    private Long ownerId;
    private String ownerUsername;
    private String basicAuthUsername;
    private boolean hasBasicAuth;
    private boolean offline;

    public static HomeServerDto from(HomeServer server) {
        HomeServerDto dto = new HomeServerDto();
        dto.setId(server.getId());
        dto.setName(server.getName());
        dto.setUrl(server.getUrl());
        dto.setOwnerId(server.getOwner().getId());
        dto.setOwnerUsername(server.getOwner().getUsername());
        dto.setBasicAuthUsername(server.getBasicAuthUsername());
        dto.setHasBasicAuth(server.getBasicAuthPassword() != null && !server.getBasicAuthPassword().isBlank());
        dto.setOffline(server.isOffline());
        return dto;
    }
}
