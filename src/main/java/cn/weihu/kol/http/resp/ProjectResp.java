package cn.weihu.kol.http.resp;

import io.swagger.annotations.ApiModel;
import lombok.Getter;
import lombok.Setter;

/**
 * @author lql
 * @date 2021/11/10 20:12
 * Description：
 */
@Setter
@Getter
@ApiModel(value = "项目信息响应实体类", description = "项目信息")
public class ProjectResp {

    private String name;

    private String descs;

    private Long ctime;
}
