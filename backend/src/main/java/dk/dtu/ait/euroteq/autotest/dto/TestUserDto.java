package dk.dtu.ait.euroteq.autotest.dto;

import dk.dtu.ait.euroteq.autotest.entity.AcademicLevel;
import dk.dtu.ait.euroteq.autotest.entity.TestUser;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class TestUserDto {

    private Long id;
    private String name;
    private String username;
    private String claims;
    private AcademicLevel academicLevel;
    private boolean alwaysDenied;
    private Long homeServerId;

    public static TestUserDto from(TestUser user) {
        TestUserDto dto = new TestUserDto();
        dto.setId(user.getId());
        dto.setName(user.getName());
        dto.setUsername(user.getUsername());
        dto.setClaims(user.getClaims());
        dto.setAcademicLevel(user.getAcademicLevel());
        dto.setAlwaysDenied(user.isAlwaysDenied());
        dto.setHomeServerId(user.getHomeServer().getId());
        return dto;
    }
}
