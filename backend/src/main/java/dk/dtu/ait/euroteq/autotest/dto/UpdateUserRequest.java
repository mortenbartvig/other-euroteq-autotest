package dk.dtu.ait.euroteq.autotest.dto;

import dk.dtu.ait.euroteq.autotest.entity.AppUser;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UpdateUserRequest {

    private AppUser.Role role;
}
