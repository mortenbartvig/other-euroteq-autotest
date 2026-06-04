package dk.dtu.ait.euroteq.autotest.dto;

import dk.dtu.ait.euroteq.autotest.entity.Result;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ResultDto {

    private Long id;
    private String name;
    private String state;
    private String pass;
    private String comment;
    private String score;
    private String resultDate;
    private String ext;
    private String studyLoad;
    private Long hostServerId;

    public static ResultDto from(Result result) {
        ResultDto dto = new ResultDto();
        dto.setId(result.getId());
        dto.setName(result.getName());
        dto.setState(result.getState());
        dto.setPass(result.getPass());
        dto.setComment(result.getComment());
        dto.setScore(result.getScore());
        dto.setResultDate(result.getResultDate());
        dto.setExt(result.getExt());
        dto.setStudyLoad(result.getStudyLoad());
        dto.setHostServerId(result.getHostServer().getId());
        return dto;
    }
}
