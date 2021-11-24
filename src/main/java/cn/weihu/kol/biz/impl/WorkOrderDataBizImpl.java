package cn.weihu.kol.biz.impl;

import cn.hutool.core.date.DateUtil;
import cn.weihu.base.exception.CheckException;
import cn.weihu.kol.biz.FieldsBiz;
import cn.weihu.kol.biz.PricesLogsBiz;
import cn.weihu.kol.biz.QuoteBiz;
import cn.weihu.kol.biz.WorkOrderDataBiz;
import cn.weihu.kol.biz.bo.FieldsBo;
import cn.weihu.kol.constants.Constants;
import cn.weihu.kol.convert.WorkOrderConverter;
import cn.weihu.kol.db.dao.ProjectDao;
import cn.weihu.kol.db.dao.WorkOrderDao;
import cn.weihu.kol.db.dao.WorkOrderDataDao;
import cn.weihu.kol.db.po.*;
import cn.weihu.kol.http.req.*;
import cn.weihu.kol.http.resp.WorkOrderDataCompareResp;
import cn.weihu.kol.http.resp.WorkOrderDataResp;
import cn.weihu.kol.http.resp.WorkOrderDataScreeningResp;
import cn.weihu.kol.runner.StartupRunner;
import cn.weihu.kol.userinfo.UserInfoContext;
import cn.weihu.kol.util.EasyExcelUtil;
import cn.weihu.kol.util.GsonUtils;
import cn.weihu.kol.util.MD5Util;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 工单数据表 服务实现类
 * </p>
 *
 * @author Lql
 * @since 2021-11-10
 */
@Slf4j
@Service
public class WorkOrderDataBizImpl extends ServiceImpl<WorkOrderDataDao, WorkOrderData> implements WorkOrderDataBiz {


    @Autowired
    private FieldsBiz     fieldsBiz;
    @Autowired
    private PricesBizImpl pricesBiz;
    @Autowired
    private PricesLogsBiz pricesLogsBiz;
    @Autowired
    private QuoteBiz      quoteBiz;
    @Resource
    private ProjectDao    projectDao;
    @Resource
    private WorkOrderDao  workOrderDao;

    @Override
    public List<WorkOrderDataResp> workOrderDataList(WorkOrderDataReq req) {
        LambdaQueryWrapper<WorkOrderData> wrapper = Wrappers.lambdaQuery(WorkOrderData.class);
        wrapper.eq(WorkOrderData::getWorkOrderId, req.getWorkOrderId())
                .eq(StringUtils.isNotBlank(req.getStatus()), WorkOrderData::getStatus, req.getStatus())
                .like(StringUtils.isNotBlank(req.getSupplier()), WorkOrderData::getData, req.getSupplier());
        List<WorkOrderData> list = list(wrapper);
        return list.stream().map(WorkOrderConverter::entity2WorkOrderDataResp).collect(Collectors.toList());
    }

    @Override
    public List<WorkOrderDataResp> waitWorkOrderDataList(WorkOrderDataReq req) {
        LambdaQueryWrapper<WorkOrderData> wrapper = Wrappers.lambdaQuery(WorkOrderData.class);
        wrapper.eq(WorkOrderData::getWorkOrderId, req.getWorkOrderId())
                .in(WorkOrderData::getStatus, Constants.WORK_ORDER_DATA_ASK_PRICE, Constants.WORK_ORDER_DATA_ASK_DATE);
        if(StartupRunner.SUPPLIER_USER_XIN_YI == UserInfoContext.getUserId()) {
            // 新意
            wrapper.like(WorkOrderData::getData, Constants.SUPPLIER_XIN_YI);
        } else {
            wrapper.like(WorkOrderData::getData, Constants.SUPPLIER_WEI_GE);
        }
        List<WorkOrderData> list = list(wrapper);
        return list.stream().map(WorkOrderConverter::entity2WorkOrderDataResp).collect(Collectors.toList());
    }

    @Override
    public Long updateWorkOrderData(WorkOrderBatchUpdateReq req) {
        if(CollectionUtils.isEmpty(req.getList())) {
            throw new CheckException("更新内容不能为空");
        }
        return null;
    }

    @Override
    public WorkOrderDataScreeningResp screening(WorkOrderBatchUpdateReq req) {
        if(CollectionUtils.isEmpty(req.getList())) {
            throw new CheckException("筛选内容不能为空");
        }
        Type type = new TypeToken<Map<String, String>>() {
        }.getType();
        List<WorkOrderDataResp> list          = new ArrayList<>();
        WorkOrderDataResp       workOrderDataResp;
        Map<String, String>     map;
        String                  actorSn;
        WorkOrderData           workOrderData = null;
        List<WorkOrderData>     updateList    = new ArrayList<>();
        for(WorkOrderDataUpdateReq updateReq : req.getList()) {
            workOrderDataResp = new WorkOrderDataResp();
            // 根据 媒体、账号ID、资源位置 匹配相同需求数据
            map = GsonUtils.gson.fromJson(updateReq.getData(), type);
            actorSn = MD5Util.getMD5(StringUtils.join(map.get(Constants.TITLE_MEDIA),
                                                      map.get(Constants.TITLE_ID_OR_LINK),
                                                      map.get(Constants.TITLE_RESOURCE_LOCATION)));
            log.info(">>> actor_sn:{}", actorSn);
            boolean flag = true;
            // kol表
            Prices prices = pricesBiz.getOneByActorSn(actorSn);
            if(Objects.nonNull(prices)) {
                // 比对 含电商连接单价、@、话题、电商肖像权、品牌双微转发授权、微任务 是否为库内数据
                flag = screeningOther(prices.getActorData(), updateReq.getData());
            } else {
                flag = false;
            }
            if(!flag) {
                // kol库匹配失败
                // 报价表
                Quote quote = quoteBiz.getOneByActorSn(req.getProjectId(), actorSn);
                if(Objects.nonNull(quote)) {
                    // 比对 含电商连接单价、@、话题、电商肖像权、品牌双微转发授权、微任务 是否为库内数据
                    flag = screeningOther(quote.getActorData(), updateReq.getData());
                    workOrderDataResp.setWorkOrderDataId(updateReq.getId());
                    workOrderDataResp.setFieldsId(updateReq.getFieldsId());
                    workOrderDataResp.setWorkOrderId(updateReq.getWorkOrderId());
                    workOrderDataResp.setStatus(Constants.WORK_ORDER_DATA_NEW);
                    if(flag) {
                        // 报价库库匹配成功
                        map = GsonUtils.gson.fromJson(quote.getActorData(), type);
                        // 填充数据
                        fillOther(map, updateReq.getData());
                    }
                } else {
                    // 库外数据
                    workOrderDataResp.setWorkOrderDataId(updateReq.getId());
                    workOrderDataResp.setFieldsId(updateReq.getFieldsId());
                    workOrderDataResp.setWorkOrderId(updateReq.getWorkOrderId());
                    workOrderDataResp.setStatus(Constants.WORK_ORDER_DATA_NEW);
                }
                map.put(Constants.ACTOR_DATA_SN, actorSn);
                map.put(Constants.ACTOR_INBOUND, "0");
                workOrderDataResp.setData(GsonUtils.gson.toJson(map));
                workOrderDataResp.setInbound(0);
            } else {
                // kol库匹配成功
                // 库内数据
                workOrderDataResp.setWorkOrderDataId(updateReq.getId());
                workOrderDataResp.setFieldsId(updateReq.getFieldsId());
                workOrderDataResp.setWorkOrderId(updateReq.getWorkOrderId());
                workOrderDataResp.setStatus(Constants.WORK_ORDER_DATA_NEW);
                map = GsonUtils.gson.fromJson(prices.getActorData(), type);
                map.put(Constants.ACTOR_DATA_SN, actorSn);
                map.put(Constants.ACTOR_INBOUND, "1");
                // 填充数据
                fillOther(map, updateReq.getData());
                workOrderDataResp.setData(GsonUtils.gson.toJson(map));
                workOrderDataResp.setInbound(1);
            }
            list.add(workOrderDataResp);
            workOrderData = new WorkOrderData();
            workOrderData.setId(updateReq.getId());
            workOrderData.setData(GsonUtils.gson.toJson(map));
            updateList.add(workOrderData);
        }
        this.updateBatchById(updateList);

        WorkOrderDataScreeningResp resp = new WorkOrderDataScreeningResp();
        // 标题
        Fields fields = fieldsBiz.getOneByType(Constants.FIELD_TYPE_DEMAND);
        type = new TypeToken<List<FieldsBo>>() {
        }.getType();
        List<FieldsBo> titles = GsonUtils.gson.fromJson(fields.getFieldList(), type);
        resp.setTitles(titles);
        // 数据
        resp.setList(list);
        return resp;
    }

    private boolean screeningOther(String inbound, String outbound) {
        Type type = new TypeToken<Map<String, String>>() {
        }.getType();
        log.info(">>> inbound:{}", inbound);
        log.info(">>> outbound:{}", outbound);
        try {
            Map<String, String> inboundMap  = GsonUtils.gson.fromJson(inbound, type);
            Map<String, String> outboundMap = GsonUtils.gson.fromJson(outbound, type);
            if("否".equals(inboundMap.get(Constants.TITLE_LINK_PRICE)) && "是".equals(outboundMap.get(Constants.TITLE_LINK_PRICE))) {
                return false;
            }
            log.info(">>> 含电商链接单价匹配成功...inbound:{},outbound:{}",
                     inboundMap.get(Constants.TITLE_LINK_PRICE), outboundMap.get(Constants.TITLE_LINK_PRICE));
            if("否".equals(inboundMap.get(Constants.TITLE_AT)) && "是".equals(outboundMap.get(Constants.TITLE_AT))) {
                return false;
            }
            log.info(">>> AT匹配成功...inbound:{},outbound:{}",
                     inboundMap.get(Constants.TITLE_AT), outboundMap.get(Constants.TITLE_AT));
            if("否".equals(inboundMap.get(Constants.TITLE_TOPIC)) && "是".equals(outboundMap.get(Constants.TITLE_TOPIC))) {
                return false;
            }
            log.info(">>> 话题匹配成功...inbound:{},outbound:{}",
                     inboundMap.get(Constants.TITLE_TOPIC), outboundMap.get(Constants.TITLE_TOPIC));
            if("否".equals(inboundMap.get(Constants.TITLE_STORE_AUTH)) && "是".equals(outboundMap.get(Constants.TITLE_STORE_AUTH))) {
                return false;
            }
            log.info(">>> 电商肖像权匹配成功...inbound:{},outbound:{}",
                     inboundMap.get(Constants.TITLE_STORE_AUTH), outboundMap.get(Constants.TITLE_STORE_AUTH));
            if("否".equals(inboundMap.get(Constants.TITLE_SHARE_AUTH)) && "是".equals(outboundMap.get(Constants.TITLE_SHARE_AUTH))) {
                return false;
            }
            log.info(">>> 品牌双微转发授权匹配成功...inbound:{},outbound:{}",
                     inboundMap.get(Constants.TITLE_SHARE_AUTH), outboundMap.get(Constants.TITLE_SHARE_AUTH));
            if("否".equals(inboundMap.get(Constants.TITLE_MICRO_TASK)) && "是".equals(outboundMap.get(Constants.TITLE_MICRO_TASK))) {
                return false;
            }
            log.info(">>> 微任务匹配成功...inbound:{},outbound:{}",
                     inboundMap.get(Constants.TITLE_MICRO_TASK), outboundMap.get(Constants.TITLE_MICRO_TASK));
        } catch(Exception e) {
            log.error(">>> 库内外信息数据匹配异常,inbound:{},outbound:{}", inbound, outbound, e);
            return false;
        }
        return true;
    }

    private void fillOther(Map<String, String> inbound, String outbound) {
        Type type = new TypeToken<Map<String, String>>() {
        }.getType();
        try {
            Map<String, String> outboundMap = GsonUtils.gson.fromJson(outbound, type);
            inbound.put(Constants.ACTOR_COUNT, outboundMap.get(Constants.ACTOR_COUNT));
            inbound.put(Constants.ACTOR_POST_START_TIME, outboundMap.get(Constants.ACTOR_POST_START_TIME));
            inbound.put(Constants.ACTOR_POST_END_TIME, outboundMap.get(Constants.ACTOR_POST_END_TIME));
            inbound.put(Constants.ACTOR_OTHER, outboundMap.get(Constants.ACTOR_OTHER));
            inbound.put(Constants.ACTOR_PRODUCT, outboundMap.get(Constants.ACTOR_PRODUCT));
            inbound.put(Constants.ACTOR_BRIEF, outboundMap.get(Constants.ACTOR_BRIEF));
            log.info(">>> 库内数据填充完成,inbound:{}", inbound);
        } catch(Exception e) {
            log.error(">>> 库内数据填充异常,inbound:{}", inbound, e);
        }
    }

    @Override
    public Long enquiry(WorkOrderBatchUpdateReq req) {
        if(CollectionUtils.isEmpty(req.getList())) {
            throw new CheckException("询价&询档内容不能为空");
        }
        WorkOrder workOrder = workOrderDao.selectById(req.getWorkOrderId());
        if(Objects.isNull(workOrder)) {
            throw new CheckException("需求工单不存在");
        }
        // 更新工单数据
        Type type = new TypeToken<Map<String, String>>() {
        }.getType();
        Map<String, String> map;
        List<WorkOrderData> workOrderDataList    = new ArrayList<>();
        List<WorkOrderData> workOrderDataListNew = new ArrayList<>();
        WorkOrderData       workOrderData;
        WorkOrderData       workOrderDataNew;
        String              compareFlag;
        boolean             existXinYi           = false;
        boolean             existWeiGe           = false;
        for(WorkOrderDataUpdateReq workOrderDataUpdateReq : req.getList()) {
            workOrderData = new WorkOrderData();
            workOrderData.setId(workOrderDataUpdateReq.getId());
            map = GsonUtils.gson.fromJson(workOrderDataUpdateReq.getData(), type);
            if(1 == workOrderDataUpdateReq.getAskType()) {
                // 询价
                // 不存在供应商
                workOrderData.setStatus(Constants.WORK_ORDER_DATA_ASK_PRICE);
                map.put(Constants.SUPPLIER_FIELD, Constants.SUPPLIER_XIN_YI);
                map.put(Constants.ACTOR_INBOUND, "0");
                compareFlag = StringUtils.join(map.get(Constants.TITLE_MEDIA),
                                               map.get(Constants.TITLE_ID_OR_LINK),
                                               map.get(Constants.TITLE_RESOURCE_LOCATION));
                map.put(Constants.ACTOR_COMPARE_FLAG, MD5Util.getMD5(compareFlag));
                workOrderData.setData(GsonUtils.gson.toJson(map));
                // 同步新增一条数据
                workOrderDataNew = new WorkOrderData();
                workOrderDataNew.setFieldsId(workOrderDataUpdateReq.getFieldsId());
                workOrderDataNew.setProjectId(workOrderDataUpdateReq.getProjectId());
                workOrderDataNew.setWorkOrderId(workOrderDataUpdateReq.getWorkOrderId());
                workOrderDataNew.setStatus(Constants.WORK_ORDER_DATA_ASK_PRICE);
                map = GsonUtils.gson.fromJson(workOrderDataUpdateReq.getData(), type);
                map.put(Constants.SUPPLIER_FIELD, Constants.SUPPLIER_WEI_GE);
                map.put(Constants.ACTOR_INBOUND, "0");
                map.put(Constants.ACTOR_COMPARE_FLAG, MD5Util.getMD5(compareFlag));
                workOrderDataNew.setData(GsonUtils.gson.toJson(map));
                workOrderDataNew.setCtime(DateUtil.date());
                workOrderDataNew.setUtime(DateUtil.date());
                workOrderDataListNew.add(workOrderDataNew);
                existXinYi = true;
                existWeiGe = true;
            } else {
                // 询档
                // 存在供应商
                workOrderData.setStatus(Constants.WORK_ORDER_DATA_ASK_DATE);
                map.put(Constants.ACTOR_INBOUND, "1");
                workOrderData.setData(GsonUtils.gson.toJson(map));
                if(Constants.SUPPLIER_XIN_YI.equals(map.get(Constants.SUPPLIER_FIELD))) {
                    existXinYi = true;
                }
                if(Constants.SUPPLIER_WEI_GE.equals(map.get(Constants.SUPPLIER_FIELD))) {
                    existWeiGe = true;
                }
            }
            workOrderData.setUtime(DateUtil.date());
            workOrderData.setUpdateUserId(UserInfoContext.getUserId());
            workOrderDataList.add(workOrderData);
        }
        updateBatchById(workOrderDataList);
        if(!workOrderDataListNew.isEmpty()) {
            saveBatch(workOrderDataListNew);
        }
        // 生成 询价/询档工单
        if(existXinYi) {
            createPointWorkOrder(workOrder, StartupRunner.SUPPLIER_USER_XIN_YI);
        }
        if(existWeiGe) {
            createPointWorkOrder(workOrder, StartupRunner.SUPPLIER_USER_WEI_GE);
        }
        // 更新需求工单状态
        workOrder.setStatus(Constants.WORK_ORDER_ASK);
        workOrder.setUpdateUserId(UserInfoContext.getUserId());
        workOrder.setUtime(DateUtil.date());
        workOrderDao.updateById(workOrder);
        return null;
    }

    private void createPointWorkOrder(WorkOrder workOrder, Long userId) {
        WorkOrder askWorkOrder = new WorkOrder();
        askWorkOrder.setOrderSn(workOrder.getOrderSn());
        askWorkOrder.setName(workOrder.getName());
        askWorkOrder.setType(Constants.WORK_ORDER_ENQUIRY);
        askWorkOrder.setProjectId(workOrder.getProjectId());
        askWorkOrder.setProjectName(workOrder.getProjectName());
        askWorkOrder.setParentId(workOrder.getId());
        askWorkOrder.setStatus(Constants.WORK_ORDER_ASK);
        askWorkOrder.setToUser(userId);
        askWorkOrder.setCtime(DateUtil.date());
        askWorkOrder.setUtime(DateUtil.date());
        askWorkOrder.setCreateUserId(UserInfoContext.getUserId());
        askWorkOrder.setUpdateUserId(UserInfoContext.getUserId());
        workOrderDao.insert(askWorkOrder);
    }

    @Override
    public Long enquiryAgain(WorkOrderBatchUpdateReq req) {
        // 请求类型:1,重新询价;2:到期询价
        if(1 != req.getRequestType() && 2 != req.getRequestType()) {
            throw new CheckException("请求类型不合法");
        }
        switch(req.getRequestType()) {
            case 1:
                // 重新询价
                again(req);
                break;
            case 2:
                // 到期询价
                expire();
        }
        return null;
    }

    private void again(WorkOrderBatchUpdateReq req) {
        if(Objects.isNull(req.getWorkOrderId())) {
            throw new CheckException("需求工单ID不能为空");
        }
        if(CollectionUtils.isEmpty(req.getList())) {
            throw new CheckException("重新询价数据不能为空");
        }
        List<WorkOrderData> list = new ArrayList<>();
        WorkOrderData       workOrderData;
        Type type = new TypeToken<Map<String, String>>() {
        }.getType();
        Map<String, String> map;
        boolean             existXinYi = false;
        boolean             existWeiGe = false;
        for(WorkOrderDataUpdateReq workOrderDataUpdateReq : req.getList()) {
            workOrderData = new WorkOrderData();
            workOrderData.setId(workOrderDataUpdateReq.getId());
            map = GsonUtils.gson.fromJson(workOrderDataUpdateReq.getData(), type);
            if("1".equals(map.get(Constants.ACTOR_INBOUND))) {
                workOrderData.setStatus(Constants.WORK_ORDER_DATA_ASK_DATE);
            } else {
                workOrderData.setStatus(Constants.WORK_ORDER_DATA_ASK_PRICE);
            }
            workOrderData.setData(workOrderDataUpdateReq.getData());
            workOrderData.setUtime(DateUtil.date());
            workOrderData.setUpdateUserId(UserInfoContext.getUserId());
            list.add(workOrderData);
            //
            if(Constants.SUPPLIER_XIN_YI.equals(map.get(Constants.SUPPLIER_FIELD))) {
                existXinYi = true;
                continue;
            }
            if(Constants.SUPPLIER_WEI_GE.equals(map.get(Constants.SUPPLIER_FIELD))) {
                existWeiGe = true;
            }
        }
        updateBatchById(list);
        // 更新询价子工单状态
        if(existXinYi) {
            WorkOrder workOrder = new WorkOrder();
            workOrder.setType(Constants.WORK_ORDER_ENQUIRY_AGAIN);
            workOrder.setStatus(Constants.WORK_ORDER_ASK);
            workOrder.setUtime(DateUtil.date());
            workOrder.setUpdateUserId(UserInfoContext.getUserId());
            workOrderDao.update(workOrder, new LambdaUpdateWrapper<>(WorkOrder.class)
                    .eq(WorkOrder::getParentId, req.getWorkOrderId())
                    .eq(WorkOrder::getToUser, StartupRunner.SUPPLIER_USER_XIN_YI));
        }
        if(existWeiGe) {
            WorkOrder workOrder = new WorkOrder();
            workOrder.setType(Constants.WORK_ORDER_ENQUIRY_AGAIN);
            workOrder.setStatus(Constants.WORK_ORDER_ASK);
            workOrder.setUtime(DateUtil.date());
            workOrder.setUpdateUserId(UserInfoContext.getUserId());
            workOrderDao.update(workOrder, new LambdaUpdateWrapper<>(WorkOrder.class)
                    .eq(WorkOrder::getParentId, req.getWorkOrderId())
                    .eq(WorkOrder::getToUser, StartupRunner.SUPPLIER_USER_WEI_GE));
        }
        // 更新工单状态
        WorkOrder workOrder = new WorkOrder();
        workOrder.setId(req.getWorkOrderId());
        workOrder.setStatus(Constants.WORK_ORDER_ASK);
        workOrder.setUtime(DateUtil.date());
        workOrder.setUpdateUserId(UserInfoContext.getUserId());
        workOrderDao.updateById(workOrder);
    }

    private void expire() {
        List<Prices> pricesList = pricesBiz.list(new LambdaQueryWrapper<>(Prices.class)
                                                         .between(Prices::getInsureEndtime, new Date(), expireDate(new Date())));
        if(CollectionUtils.isEmpty(pricesList)) {
            throw new CheckException("重新询价数据不存在");
        }
        // 创建保价即将到期项目
        String  name    = "保价即将到期";
        Project project = projectDao.selectOne(new LambdaQueryWrapper<>(Project.class).eq(Project::getName, name));
        if(Objects.isNull(project)) {
            project = new Project();
            project.setName(name);
            project.setDescs("保价即将到期项目");
            project.setCtime(LocalDateTime.now());
            project.setUtime(LocalDateTime.now());
            projectDao.insert(project);
        }
        log.info(">>> 保价即将到期项目已生成...");
        // 生成保价到期询价工单
        WorkOrder workOrder = new WorkOrder();
        workOrder.setProjectId(project.getId());
        workOrder.setProjectName(project.getName());
//        workOrder = workOrderDao.create(workOrder, Constants.WORK_ORDER_EXPIRE_DEMAND, Constants.WORK_ORDER_ASK);
        log.info(">>> 保价即将到期工单已生成...");
        // 生成工单数据及提醒消息
        List<WorkOrderData> workOrderDataList = new ArrayList<>();
        WorkOrderData       workOrderData;
        Type type = new TypeToken<Map<String, String>>() {
        }.getType();
        Map<String, String> map;
        boolean             existXinYi = false;
        boolean             existWeiGe = false;
        for(Prices prices : pricesList) {
            workOrderData = new WorkOrderData();
            workOrderData.setFieldsId(1L);
            workOrderData.setProjectId(project.getId());
            workOrderData.setWorkOrderId(workOrder.getId());
            workOrderData.setStatus(Constants.WORK_ORDER_DATA_ASK_PRICE);
            workOrderData.setData(prices.getActorData());
            workOrderData.setCtime(DateUtil.date());
            workOrderData.setUtime(DateUtil.date());
            workOrderDataList.add(workOrderData);

            map = GsonUtils.gson.fromJson(prices.getActorData(), type);
            if(Constants.SUPPLIER_XIN_YI.equals(map.get(Constants.ACTOR_PROVIDER))) {
                existXinYi = true;
            }
            if(Constants.SUPPLIER_WEI_GE.equals(map.get(Constants.ACTOR_PROVIDER))) {
                existWeiGe = true;
            }
        }
        saveBatch(workOrderDataList);
        //
        if(existXinYi) {
            WorkOrder workOrderXinYi = new WorkOrder();
            workOrderXinYi.setOrderSn(workOrder.getOrderSn());
            workOrderXinYi.setName(workOrder.getName());
            workOrderXinYi.setType(Constants.WORK_ORDER_ENQUIRY);
            workOrderXinYi.setProjectId(workOrder.getProjectId());
            workOrderXinYi.setProjectName(workOrder.getProjectName());
            workOrderXinYi.setParentId(workOrder.getId());
            workOrderXinYi.setStatus(Constants.WORK_ORDER_ASK);
            workOrderXinYi.setToUser(StartupRunner.SUPPLIER_USER_XIN_YI);
            workOrderXinYi.setCtime(DateUtil.date());
            workOrderXinYi.setUtime(DateUtil.date());
            workOrderDao.insert(workOrderXinYi);
            log.info(">>> 保价即将到期新意询价子工单已生成...");
        }
        if(existWeiGe) {
            WorkOrder workOrderWeiGe = new WorkOrder();
            workOrderWeiGe.setOrderSn(workOrder.getOrderSn());
            workOrderWeiGe.setName(workOrder.getName());
            workOrderWeiGe.setType(Constants.WORK_ORDER_ENQUIRY);
            workOrderWeiGe.setProjectId(workOrder.getProjectId());
            workOrderWeiGe.setProjectName(workOrder.getProjectName());
            workOrderWeiGe.setParentId(workOrder.getId());
            workOrderWeiGe.setStatus(Constants.WORK_ORDER_ASK);
            workOrderWeiGe.setToUser(StartupRunner.SUPPLIER_USER_XIN_YI);
            workOrderWeiGe.setCtime(DateUtil.date());
            workOrderWeiGe.setUtime(DateUtil.date());
            workOrderDao.insert(workOrderWeiGe);
            log.info(">>> 保价即将到期微格询价子工单已生成...");
        }
    }


    @Override
    public Long quote(WorkOrderBatchUpdateReq req) {
        if(CollectionUtils.isEmpty(req.getList())) {
            throw new CheckException("报价工单数据不能为空");
        }
        // 更新工单数据状态
        List<WorkOrderData> workOrderDataList = new ArrayList<>();
        WorkOrderData       workOrderData;
        for(WorkOrderDataUpdateReq workOrderDataUpdateReq : req.getList()) {
            workOrderData = new WorkOrderData();
            workOrderData.setId(workOrderDataUpdateReq.getId());
            workOrderData.setStatus(Constants.WORK_ORDER_DATA_QUOTE);
            workOrderData.setData(workOrderDataUpdateReq.getData());
            workOrderData.setUtime(DateUtil.date());
            workOrderData.setUpdateUserId(UserInfoContext.getUserId());
            workOrderDataList.add(workOrderData);
        }
        updateBatchById(workOrderDataList);
        // 更新询价工单状态
        WorkOrder workOrder = workOrderDao.selectById(req.getWorkOrderId());
        workOrder.setStatus(Constants.WORK_ORDER_QUOTE);
        workOrder.setUpdateUserId(UserInfoContext.getUserId());
        workOrder.setUtime(DateUtil.date());
        workOrderDao.updateById(workOrder);
        // 更新父工单状态
        LambdaQueryWrapper<WorkOrderData> wrapper = Wrappers.lambdaQuery(WorkOrderData.class);
        wrapper.eq(WorkOrderData::getWorkOrderId, workOrder.getParentId())
                .in(WorkOrderData::getStatus, Constants.WORK_ORDER_DATA_ASK_PRICE, Constants.WORK_ORDER_DATA_ASK_DATE);
        List<WorkOrderData> list = list(wrapper);
        if(CollectionUtils.isEmpty(list)) {
            // 更新工单状态为 已报价
            WorkOrder workOrderParent = new WorkOrder();
            workOrderParent.setId(workOrder.getParentId());
            workOrderParent.setStatus(Constants.WORK_ORDER_QUOTE);
            workOrderParent.setUpdateUserId(UserInfoContext.getUserId());
            workOrderParent.setUtime(DateUtil.date());
            workOrderDao.updateById(workOrderParent);
        }
        return null;
    }

    @Override
    public WorkOrderDataCompareResp quoteList(WorkOrderDataReq req) {
        List<WorkOrderData> list = list(new LambdaQueryWrapper<>(WorkOrderData.class)
                                                .eq(WorkOrderData::getWorkOrderId, req.getWorkOrderId())
                                                .eq(WorkOrderData::getStatus, Constants.WORK_ORDER_DATA_QUOTE));
        WorkOrderDataCompareResp resp = null;
        if(!CollectionUtils.isEmpty(list)) {
            resp = new WorkOrderDataCompareResp();
            Type type = new TypeToken<Map<String, String>>() {
            }.getType();
            List<WorkOrderDataResp> outboundList = new ArrayList<>();
            List<WorkOrderDataResp> xinYiList    = new ArrayList<>();
            List<WorkOrderDataResp> weiGeList    = new ArrayList<>();
            WorkOrderDataResp       outbound;
            WorkOrderDataResp       xinYi;
            WorkOrderDataResp       weiGe;
            Map<String, String>     map;
            for(WorkOrderData workOrderData : list) {
                map = GsonUtils.gson.fromJson(workOrderData.getData(), type);
                String inbound = map.get(Constants.ACTOR_INBOUND);
                if(!"1".equals(inbound)) {
                    // 库外
                    outbound = WorkOrderConverter.entity2WorkOrderDataResp(workOrderData);
                    outbound.setCompareFlag(map.get(Constants.ACTOR_COMPARE_FLAG));
                    outboundList.add(outbound);
                } else {
                    // 库内 区分供应商
                    String supplier = map.get(Constants.SUPPLIER_FIELD);
                    if(Constants.SUPPLIER_XIN_YI.equals(supplier)) {
                        // 新意
                        xinYi = WorkOrderConverter.entity2WorkOrderDataResp(workOrderData);
                        xinYiList.add(xinYi);
                    } else {
                        // 维格
                        weiGe = WorkOrderConverter.entity2WorkOrderDataResp(workOrderData);
                        weiGeList.add(weiGe);
                    }
                }
            }
            resp.setXinYiList(xinYiList);
            resp.setWeiGeList(weiGeList);
            //
            Map<String, List<WorkOrderDataResp>> outboundMap = outboundList
                    .stream()
                    .collect(Collectors.groupingBy(WorkOrderDataResp::getCompareFlag, Collectors.toList()));
            resp.setOutboundMap(outboundMap);
        }
        return resp;
    }

    @Override
    public Long order(WorkOrderDataOrderReq req) {
        if(CollectionUtils.isEmpty(req.getWorkOrderDataIds())) {
            throw new CheckException("提审下单工单数据不能为空");
        }
        // 更新工单数据状态
        List<WorkOrderData> workOrderDataList = new ArrayList<>();
        WorkOrderData       workOrderData;
        for(Long workOrderDataId : req.getWorkOrderDataIds()) {
            workOrderData = new WorkOrderData();
            workOrderData.setId(workOrderDataId);
            workOrderData.setStatus(Constants.WORK_ORDER_DATA_REVIEW);
            workOrderData.setUtime(DateUtil.date());
            workOrderData.setUpdateUserId(UserInfoContext.getUserId());
            workOrderDataList.add(workOrderData);
        }
        updateBatchById(workOrderDataList);
        // 处理未勾选的数据状态 新增至报价表
        List<WorkOrderData> list = list(new LambdaQueryWrapper<>(WorkOrderData.class).eq(WorkOrderData::getWorkOrderId, req.getWorkOrderId())
                                                .ne(WorkOrderData::getStatus, Constants.WORK_ORDER_DATA_REVIEW));
        if(!CollectionUtils.isEmpty(list)) {
            workOrderDataList.clear();
            List<Quote> quoteList = new ArrayList<>();
            Quote       quote;
            Type type = new TypeToken<Map<String, String>>() {
            }.getType();
            Map<String, String> map;
            for(WorkOrderData unSelect : list) {
                unSelect.setStatus(Constants.WORK_ORDER_DATA_QUOTE_UNSELECTED);
                unSelect.setUtime(DateUtil.date());
                unSelect.setUpdateUserId(UserInfoContext.getUserId());
                workOrderDataList.add(unSelect);

                quote = new Quote();
                quote.setProjectId(req.getProjectId());
                map = GsonUtils.gson.fromJson(unSelect.getData(), type);
                quote.setActorSn(map.get(Constants.ACTOR_DATA_SN));
                quote.setActorData(unSelect.getData());
                quote.setCommission(StringUtils.isNotBlank(map.get(Constants.ACTOR_COMMISSION)) ?
                                    Integer.parseInt(map.get(Constants.ACTOR_COMMISSION)) : null);
                quote.setPrice(StringUtils.isNotBlank(map.get(Constants.ACTOR_PRICE)) ?
                               Double.parseDouble(map.get(Constants.ACTOR_PRICE)) : null);
                quote.setProvider(map.get(Constants.ACTOR_PROVIDER));
                // 保价到期时间14天后
                quote.setInsureEndtime(DateUtil.offsetDay(DateUtil.date(), 14));
                quote.setEnableFlag(1);
                quote.setCtime(DateUtil.date());
                quote.setUtime(DateUtil.date());
                quote.setCreateUserId(UserInfoContext.getUserId());
                quote.setUpdateUserId(UserInfoContext.getUserId());
                quoteList.add(quote);
            }
            updateBatchById(workOrderDataList);
            quoteBiz.saveBatch(quoteList);
        }
        // 更新工单状态为 审核中
        WorkOrder workOrder = new WorkOrder();
        workOrder.setId(req.getWorkOrderId());
        workOrder.setStatus(Constants.WORK_ORDER_REVIEW);
        workOrder.setUpdateUserId(UserInfoContext.getUserId());
        workOrder.setUtime(DateUtil.date());
        workOrderDao.updateById(workOrder);
        return null;
    }

    @Override
    public Long review(WorkOrderDataReviewReq req) {
        if(CollectionUtils.isEmpty(req.getWorkOrderDataIds())) {
            throw new CheckException("审核工单数据不能为空");
        }
        if(!StringUtils.equalsAny(req.getStatus(), Constants.WORK_ORDER_DATA_REVIEW_PASS,
                                  Constants.WORK_ORDER_DATA_REVIEW_REJECT)) {
            throw new CheckException("审核状态不合法");
        }
        if(Constants.WORK_ORDER_DATA_REVIEW_REJECT.equals(req.getStatus())) {
            if(StringUtils.isBlank(req.getRejectReason())) {
                throw new CheckException("驳回原因不能为空");
            }
            // 审核驳回 返回上一步
            reviewReject(req.getWorkOrderId(), req.getRejectReason());
            log.info(">>> 审核驳回,workOrderId:{}", req.getWorkOrderId());
            return null;
        }
        List<WorkOrderData> list = list(new LambdaQueryWrapper<>(WorkOrderData.class)
                                                .in(WorkOrderData::getId, req.getWorkOrderDataIds()));
        if(CollectionUtils.isEmpty(list)) {
            throw new CheckException("审核工单数据不存在");
        }
        Type type = new TypeToken<Map<String, String>>() {
        }.getType();
        List<WorkOrderData> workOrderDataList = new ArrayList<>();
        List<Prices>        pricesList        = new ArrayList<>();
        List<PricesLogs>    pricesLogsList    = new ArrayList<>();
        List<Quote>         quoteList         = new ArrayList<>();
        Prices              prices;
        PricesLogs          pricesLogs;
        Quote               quote;
        Map<String, String> map;
        String              inbound;
        String              actorSn;
        for(WorkOrderData workOrderData : list) {
            // 更新工单数据
            workOrderData.setStatus(req.getStatus());
            workOrderData.setUtime(DateUtil.date());
            workOrderData.setUpdateUserId(UserInfoContext.getUserId());
            workOrderDataList.add(workOrderData);
            // 判断库内外,库外新增,工单数据更新记录
            map = GsonUtils.gson.fromJson(workOrderData.getData(), type);
            inbound = map.get(Constants.ACTOR_INBOUND);
            actorSn = MD5Util.getMD5(StringUtils.join(map.get(Constants.TITLE_MEDIA),
                                                      map.get(Constants.TITLE_ID_OR_LINK),
                                                      map.get(Constants.TITLE_RESOURCE_LOCATION)));
            if("0".equals(inbound)) {
                // 库外数据审核通过 入kol库,并更新报价库enable标识
                // kol资源表
                prices = new Prices();
                prices.setActorSn(actorSn);
                prices.setActorData(workOrderData.getData());
                prices.setInsureEndtime(DateUtil.offsetMonth(DateUtil.date(), 6));
                prices.setCommission(StringUtils.isNotBlank(map.get(Constants.ACTOR_COMMISSION)) ?
                                     Integer.parseInt(map.get(Constants.ACTOR_COMMISSION)) : null);
                prices.setPrice(StringUtils.isNotBlank(map.get(Constants.ACTOR_PRICE)) ?
                                Double.parseDouble(map.get(Constants.ACTOR_PRICE)) : null);
                prices.setProvider(map.get(Constants.ACTOR_PROVIDER));
                prices.setCtime(DateUtil.date());
                prices.setUtime(DateUtil.date());
                prices.setCreateUserId(UserInfoContext.getUserId());
                prices.setUpdateUserId(UserInfoContext.getUserId());
                pricesList.add(prices);

                quote = new Quote();
                quote.setActorSn(actorSn);
                quote.setProjectId(workOrderData.getProjectId());
                quote.setEnableFlag(0);
                quote.setUtime(DateUtil.date());
                quote.setUpdateUserId(UserInfoContext.getUserId());
                quoteList.add(quote);
            }
            // 报价记录表
            pricesLogs = new PricesLogs();
            pricesLogs.setActorSn(actorSn);
            pricesLogs.setActorData(workOrderData.getData());
            if(Constants.WORK_ORDER_DATA_REVIEW_PASS.equals(req.getStatus())) {
                pricesLogs.setInbound(1);
                pricesLogs.setInsureEndtime(DateUtil.offsetMonth(DateUtil.date(), 6));
            } else {
                pricesLogs.setInbound(0);
                pricesLogs.setInsureEndtime(DateUtil.offsetDay(DateUtil.date(), 14));
            }
            pricesLogs.setCommission(StringUtils.isNotBlank(map.get(Constants.ACTOR_COMMISSION)) ?
                                     Integer.parseInt(map.get(Constants.ACTOR_COMMISSION)) : null);
            pricesLogs.setPrice(StringUtils.isNotBlank(map.get(Constants.ACTOR_PRICE)) ?
                                Double.parseDouble(map.get(Constants.ACTOR_PRICE)) : null);
            pricesLogs.setProvider(map.get(Constants.ACTOR_PROVIDER));
            pricesLogs.setCtime(DateUtil.date());
            pricesLogs.setUtime(DateUtil.date());
            pricesLogs.setCreateUserId(UserInfoContext.getUserId());
            pricesLogs.setUpdateUserId(UserInfoContext.getUserId());
            pricesLogsList.add(pricesLogs);
        }
        updateBatchById(workOrderDataList);
        if(!CollectionUtils.isEmpty(pricesList)) {
            pricesBiz.saveBatch(pricesList);
        }
        if(!CollectionUtils.isEmpty(pricesLogsList)) {
            pricesLogsBiz.saveBatch(pricesLogsList);
        }
        if(!CollectionUtils.isEmpty(quoteList)) {
            quoteBiz.updateBatchByActorSn(quoteList);
        }
        // 更新工单状态 -> 已下单
        WorkOrder workOrder = new WorkOrder();
        workOrder.setId(req.getWorkOrderId());
        workOrder.setStatus(Constants.WORK_ORDER_ORDER);
        workOrder.setUtime(DateUtil.date());
        workOrder.setUpdateUserId(UserInfoContext.getUserId());
        workOrderDao.updateById(workOrder);
        return null;
    }

    @Override
    public void detailExport(WorkOrderBatchUpdateReq req, HttpServletResponse response) {

        Fields fields = fieldsBiz.getById(Constants.FIELD_TYPE_DEMAND);
        //获取字段列表
        List<FieldsBo> fieldsBos = GsonUtils.gson.fromJson(fields.getFieldList(), new TypeToken<ArrayList<FieldsBo>>() {
        }.getType());

        List<List<List<String>>> allData = new ArrayList<>();
        //库外数据
        List<List<String>> outboundData = new ArrayList<>();

        //新意
        List<List<String>> xinyiData = new ArrayList<>();

        //维格
        List<List<String>> weigeData = new ArrayList<>();

        List<FieldsBo> newList = fieldsBos.stream().filter(x -> x.isEffect()).collect(Collectors.toList());
        //获取中文表头
        List<String> titleCN = newList.stream().map(FieldsBo::getTitle).collect(Collectors.toList());

        outboundData.add(titleCN);
        xinyiData.add(titleCN);
        weigeData.add(titleCN);

        LambdaQueryWrapper<WorkOrderData> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkOrderData::getWorkOrderId, req.getWorkOrderId());
        List<WorkOrderData> orderData = baseMapper.selectList(wrapper);

        for(int i = 0; i < orderData.size(); i++) {
            WorkOrderData           workOrderData = orderData.get(i);
            List<String>            data          = new ArrayList<>();
            HashMap<String, String> hashMap       = GsonUtils.gson.fromJson(workOrderData.getData(), HashMap.class);
            if("0".equals(hashMap.get(Constants.ACTOR_INBOUND))) {
                pricesBiz.addExportData(outboundData, data, hashMap, newList);
            } else {
                String supplier = hashMap.get(Constants.SUPPLIER_FIELD);
                if(Constants.SUPPLIER_XIN_YI.equalsIgnoreCase(supplier)) {
                    pricesBiz.addExportData(xinyiData, data, hashMap, newList);
                } else if(Constants.SUPPLIER_WEI_GE.equalsIgnoreCase(supplier)) {
                    pricesBiz.addExportData(weigeData, data, hashMap, newList);
                }
            }
        }
        allData.add(outboundData);
        allData.add(xinyiData);
        allData.add(weigeData);

//        List<WorkOrderDataUpdateReq> list = req.getList();
//
//        if(list != null) {
//            for(WorkOrderDataUpdateReq orderDataUpdateReq : list) {
//                List<String> data = new ArrayList<>();
//                //库外
//                if(orderDataUpdateReq.getInbound() == 0) {
//                    pricesBiz.addExportData(outboundData, data, orderDataUpdateReq.getData(), newList);
//                } else {
//                    HashMap<String, String> hashMap  = GsonUtils.gson.fromJson(orderDataUpdateReq.getData(), HashMap.class);
//                    String                  supplier = hashMap.get(Constants.SUPPLIER_FIELD);
//                    if(Constants.SUPPLIER_XIN_YI.equalsIgnoreCase(supplier)) {
//                        pricesBiz.addExportData(xinyiData, data, orderDataUpdateReq.getData(), newList);
//                    } else if(Constants.SUPPLIER_WEI_GE.equalsIgnoreCase(supplier)) {
//                        pricesBiz.addExportData(weigeData, data, orderDataUpdateReq.getData(), newList);
//                    }
//                }
//            }
//            allData.add(outboundData);
//            allData.add(xinyiData);
//            allData.add(weigeData);
//        }
        List<String> sheetNames = Arrays.asList("库外数据", Constants.SUPPLIER_XIN_YI, Constants.SUPPLIER_WEI_GE);
        try {
            EasyExcelUtil.writeExcelSheet(response, allData, "需求单详情", sheetNames);
        } catch(
                Exception e) {
            e.printStackTrace();
        }

    }

    @Override
    public void supplierExport(WorkOrderBatchUpdateReq req, HttpServletResponse response) {

        Fields fields = fieldsBiz.getById(Constants.FIELD_TYPE_QUOTE);
        //获取字段列表
        List<FieldsBo> fieldsBos = GsonUtils.gson.fromJson(fields.getFieldList(), new TypeToken<ArrayList<FieldsBo>>() {
        }.getType());

        List<List<List<String>>> allData = new ArrayList<>();
        //报价&排期
        List<List<String>> outboundData = new ArrayList<>();
        //仅排档期
        List<List<String>> inboundData = new ArrayList<>();

        List<FieldsBo> newList = fieldsBos.stream().filter(x -> x.isEffect()).collect(Collectors.toList());
        //获取中文表头
        List<String> titleCN = newList.stream().map(FieldsBo::getTitle).collect(Collectors.toList());

        outboundData.add(titleCN);
        inboundData.add(titleCN);

        String[] split = req.getWorkerOrderDataIds().split(",");
        for(int i = 0; i < split.length; i++) {
            List<String>            data          = new ArrayList<>();
            String                  id            = split[i];
            WorkOrderData           workOrderData = getById(id);
            HashMap<String, String> hashMap       = GsonUtils.gson.fromJson(workOrderData.getData(), HashMap.class);
            if("1".equals(hashMap.get(Constants.ACTOR_INBOUND))) {
                pricesBiz.addExportData(inboundData, data, hashMap, newList);
            } else {
                pricesBiz.addExportData(outboundData, data, hashMap, newList);
            }
        }

        allData.add(outboundData);
        allData.add(inboundData);


//        List<WorkOrderDataUpdateReq> list = req.getList();
//        if(list != null) {
//            for(WorkOrderDataUpdateReq orderDataUpdateReq : list) {
//                List<String> data = new ArrayList<>();
//                //库内 排期
//                if(orderDataUpdateReq.getInbound() == 1) {
//                    pricesBiz.addExportData(inboundData, data, orderDataUpdateReq.getData(), newList);
//                }
//                pricesBiz.addExportData(outboundData, data, orderDataUpdateReq.getData(), newList);
//            }
//            allData.add(outboundData);
//            allData.add(inboundData);
//        }
        List<String> sheetNames = Arrays.asList("报价&排期", "仅排档期");

        try {
            EasyExcelUtil.writeExcelSheet(response, allData, "供应商报价", sheetNames);
        } catch(
                Exception e) {
            e.printStackTrace();
        }

    }


    private void reviewReject(Long workOrderId, String rejectReason) {
        // 更新工单数据状态 -> 已报价
        LambdaUpdateWrapper<WorkOrderData> wrapper = Wrappers.lambdaUpdate(WorkOrderData.class);
        wrapper.set(WorkOrderData::getStatus, Constants.WORK_ORDER_DATA_QUOTE)
                .set(WorkOrderData::getUtime, DateUtil.date())
                .set(WorkOrderData::getUpdateUserId, UserInfoContext.getUserId())
                .eq(WorkOrderData::getWorkOrderId, workOrderId);
        update(wrapper);
        // 更新工单状态 -> 已报价
        WorkOrder workOrder = new WorkOrder();
        workOrder.setId(workOrderId);
        workOrder.setStatus(Constants.WORK_ORDER_QUOTE);
        workOrder.setRejectReason(rejectReason);
        workOrder.setUtime(DateUtil.date());
        workOrder.setUpdateUserId(UserInfoContext.getUserId());
        workOrderDao.updateById(workOrder);
    }

    //返回30天的日子
    private Date expireDate(Date date) {
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        c.add(Calendar.DATE, StartupRunner.PRICE_EXPIRE_REMIND_DAY);
        return c.getTime();
    }
}
