package cn.weihu.kol.biz;

import cn.weihu.kol.db.po.WorkOrderData;
import cn.weihu.kol.http.req.WorkOrderBatchUpdateReq;
import cn.weihu.kol.http.req.WorkOrderDataReq;
import cn.weihu.kol.http.req.WorkOrderDataReviewReq;
import cn.weihu.kol.http.resp.WorkOrderDataResp;
import cn.weihu.kol.http.resp.WorkOrderDataScreeningResp;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * <p>
 * 工单数据表 服务类
 * </p>
 *
 * @author Lql
 * @since 2021-11-10
 */
public interface WorkOrderDataBiz extends IService<WorkOrderData> {

    List<WorkOrderDataResp> workOrderDataList(WorkOrderDataReq req);

    Long updateWorkOrderData(WorkOrderBatchUpdateReq req);

    WorkOrderDataScreeningResp screening(@RequestBody WorkOrderBatchUpdateReq req);

    Long enquiry(@RequestBody WorkOrderBatchUpdateReq req);

    Long quote(@RequestBody WorkOrderBatchUpdateReq req);

    Long review(@RequestBody WorkOrderDataReviewReq req);
}
