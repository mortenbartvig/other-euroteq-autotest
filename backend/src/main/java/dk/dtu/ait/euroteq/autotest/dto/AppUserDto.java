package dk.dtu.ait.euroteq.autotest.dto;

import dk.dtu.ait.euroteq.autotest.entity.AppUser;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AppUserDto {

    private Long id;
    private String username;
    private AppUser.Role role;
    private boolean mustChangePassword;

    public static AppUserDto from(AppUser user) {
        AppUserDto dto = new AppUserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setRole(user.getRole());
        dto.setMustChangePassword(user.isMustChangePassword());
        return dto;
    }
}
