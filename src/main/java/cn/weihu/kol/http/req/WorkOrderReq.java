package cn.weihu.kol.http.req;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * @author lql
 * @date 2021/11/10 17:35
 * Description：
 */
@Setter
@Getter
@ApiModel(value = "工单请求实体类", description = "描述")
public class WorkOrderReq {

    @ApiModelProperty(value = "页数")
    private Integer pageNo = 1;

    @ApiModelProperty(value = "条数")
    private Integer pageSize = 10;

    @ApiModelProperty(value = "工单编号/名称")
    private String name;

    @ApiModelProperty(value = "开始时间,13位毫秒级时间戳")
    private Long startTime;

    @ApiModelProperty(value = "结束时间,13位毫秒级时间戳")
    private Long endTime;
}
