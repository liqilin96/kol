package cn.weihu.kol.biz.impl;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.weihu.base.exception.CheckException;
import cn.weihu.kol.biz.*;
import cn.weihu.kol.biz.bo.FieldsBo;
import cn.weihu.kol.biz.bo.WorkOrderDataBo;
import cn.weihu.kol.constants.Constants;
import cn.weihu.kol.container.PlatformRulesContainer;
import cn.weihu.kol.convert.WorkOrderConverter;
import cn.weihu.kol.db.dao.ProjectDao;
import cn.weihu.kol.db.dao.WorkOrderDao;
import cn.weihu.kol.db.dao.WorkOrderDataDao;
import cn.weihu.kol.db.po.*;
import cn.weihu.kol.http.req.*;
import cn.weihu.kol.http.resp.SupplierImportResp;
import cn.weihu.kol.http.resp.WorkOrderDataCompareResp;
import cn.weihu.kol.http.resp.WorkOrderDataResp;
import cn.weihu.kol.http.resp.WorkOrderDataScreeningResp;
import cn.weihu.kol.runner.StartupRunner;
import cn.weihu.kol.userinfo.UserInfoContext;
import cn.weihu.kol.util.DateTimeUtils;
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
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private FieldsBiz        fieldsBiz;
    @Autowired
    private PricesBiz        pricesBiz;
    @Autowired
    private PricesLogsBiz    pricesLogsBiz;
    @Autowired
    private QuoteBiz         quoteBiz;
    @Resource
    private ProjectDao       projectDao;
    @Resource
    private WorkOrderDao     workOrderDao;
    @Autowired
    private UserBiz          userBiz;
    @Autowired
    private WorkOrderBizImpl workOrderBiz;
    @Resource
    private WorkOrderDataDao workOrderDataDao;


    ExecutorService threadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);

    @Override
    public List<WorkOrderDataResp> workOrderDataList(WorkOrderDataReq req) {
        List<WorkOrder>                   orderList = workOrderDao.selectList(new LambdaQueryWrapper<>(WorkOrder.class).eq(WorkOrder::getParentId, req.getWorkOrderId()));
        LambdaQueryWrapper<WorkOrderData> wrapper   = Wrappers.lambdaQuery(WorkOrderData.class);
        if(orderList == null || orderList.size() == 0) {
            wrapper.eq(WorkOrderData::getWorkOrderId, req.getWorkOrderId());
        } else {
            wrapper.in(WorkOrderData::getWorkOrderId, orderList.stream().map(WorkOrder::getId).collect(Collectors.toList()))
                    .eq(StringUtils.isNotBlank(req.getStatus()), WorkOrderData::getStatus, req.getStatus());
        }

        if(StringUtils.isNotBlank(req.getSupplier())) {
            wrapper.apply("JSON_UNQUOTE(JSON_EXTRACT(data,\"$.supplier\")) = {0}1", req.getSupplier());
        }
//        wrapper.groupBy(WorkOrderData::getAccount);
        wrapper.last("ORDER BY account");

        List<WorkOrderData> list = list(wrapper);
        //拼凑违约记录数据
        List<Long> idList = list.stream().map(WorkOrderData::getId).collect(Collectors.toList());
        if(idList != null && idList.size() > 0) {
            List<Prices> prices = pricesBiz.list(new LambdaQueryWrapper<>(Prices.class).in(Prices::getJoinWorkOrderDataId, idList));
            if(prices != null && prices.size() > 0) {
                List<WorkOrderData> dataList = prices.stream().map(x -> {
                    WorkOrderData workOrderData = new WorkOrderData();
                    workOrderData.setData(x.getActorData());
                    workOrderData.setId(x.getJoinWorkOrderDataId());
                    return workOrderData;
                }).collect(Collectors.toList());
                list.addAll(dataList);
            }
        }

        return list.stream().sorted((x, y) -> {
            //排序
            return x.getId().compareTo(y.getId());
        }).map(WorkOrderConverter::entity2WorkOrderDataResp).collect(Collectors.toList());
    }

    @Override
    public List<WorkOrderDataResp> waitWorkOrderDataList(WorkOrderDataReq req) {
        LambdaQueryWrapper<WorkOrderData> wrapper = Wrappers.lambdaQuery(WorkOrderData.class);
        wrapper.eq(WorkOrderData::getWorkOrderId, req.getWorkOrderId())
                .in(WorkOrderData::getStatus, Constants.WORK_ORDER_DATA_ASK_PRICE, Constants.WORK_ORDER_DATA_ASK_DATE);
        if(StartupRunner.SUPPLIER_USER_XIN_YI == UserInfoContext.getUserId()) {
            // 新意
            wrapper.apply("JSON_UNQUOTE(JSON_EXTRACT(data,\"$.supplier\")) = {0}", Constants.SUPPLIER_XIN_YI);
        } else {
            wrapper.apply("JSON_UNQUOTE(JSON_EXTRACT(data,\"$.supplier\")) = {0}", Constants.SUPPLIER_WEI_GE);
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
        WorkOrderDataResp       workOrderDataResp1;
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
            Prices       prices     = null;
            List<Prices> pricesList = pricesBiz.getListActorSn(actorSn);
            if(!CollectionUtils.isEmpty(pricesList)) {
                for(Prices p : pricesList) {
                    // 比对 含电商连接单价、@、话题、电商肖像权、品牌双微转发授权、微任务 是否为库内数据
                    flag = screeningOther(p.getActorData(), updateReq.getData());
                    if(flag) {
                        // 匹配成功
                        prices = p;
                        break;
                    }
                }
            } else {
                flag = false;
            }
            if(!flag) {
                // kol库匹配失败
                // 报价表 新意/微格
                Quote quoteXinYi = quoteBiz.getOneByActorSn(req.getProjectId(), actorSn, Constants.SUPPLIER_XIN_YI);
                Quote quoteWeiGe = quoteBiz.getOneByActorSn(req.getProjectId(), actorSn, Constants.SUPPLIER_WEI_GE);
                // 根据规则比对 是否为库内数据
                if(Objects.nonNull(quoteXinYi) || Objects.nonNull(quoteWeiGe)) {
                    //
                    if(Objects.nonNull(quoteXinYi) && Objects.nonNull(quoteWeiGe)) {
                        // 新意/微格 都存在
                        flag = screeningOther(quoteWeiGe.getActorData(), updateReq.getData());
                        if(flag) {
                            // 报价库库匹配成功
                            log.info(">>> 报价库微格数据匹配成功,actor_sn:{}", actorSn);
                            workOrderDataResp1 = new WorkOrderDataResp();
                            workOrderDataResp1.setFieldsId(updateReq.getFieldsId());
                            workOrderDataResp1.setWorkOrderId(updateReq.getWorkOrderId());
                            map = GsonUtils.gson.fromJson(quoteWeiGe.getActorData(), type);
                            // 填充数据
                            fillOther(map, updateReq.getData());
                            map.put(Constants.ACTOR_KOL_QUOTE_ID, String.valueOf(quoteWeiGe.getId()));
                            workOrderDataResp1.setStatus(Constants.WORK_ORDER_DATA_QUOTE);
                            map.put(Constants.ACTOR_DATA_SN, actorSn);
                            map.put(Constants.ACTOR_INBOUND, "0");
                            workOrderDataResp1.setData(GsonUtils.gson.toJson(map));
                            workOrderDataResp1.setInbound(0);
                            list.add(workOrderDataResp1);
                        }

                        flag = screeningOther(quoteXinYi.getActorData(), updateReq.getData());
                        if(flag) {
                            // 报价库库匹配成功
                            log.info(">>> 报价库新意数据匹配成功,actor_sn:{}", actorSn);
                            workOrderDataResp.setWorkOrderDataId(updateReq.getId());
                            workOrderDataResp.setFieldsId(updateReq.getFieldsId());
                            workOrderDataResp.setWorkOrderId(updateReq.getWorkOrderId());
                            map = GsonUtils.gson.fromJson(quoteXinYi.getActorData(), type);
                            // 填充数据
                            fillOther(map, updateReq.getData());
                            map.put(Constants.ACTOR_KOL_QUOTE_ID, String.valueOf(quoteXinYi.getId()));
                            workOrderDataResp.setStatus(Constants.WORK_ORDER_DATA_QUOTE);
                        } else {
                            workOrderDataResp.setStatus(Constants.WORK_ORDER_DATA_NEW);
                        }
                    } else {
                        // 只存在一家供应商
                        workOrderDataResp.setWorkOrderDataId(updateReq.getId());
                        workOrderDataResp.setFieldsId(updateReq.getFieldsId());
                        workOrderDataResp.setWorkOrderId(updateReq.getWorkOrderId());
                        // 新意
                        if(Objects.nonNull(quoteXinYi)) {
                            flag = screeningOther(quoteXinYi.getActorData(), updateReq.getData());
                            if(flag) {
                                // 报价库库匹配成功
                                map = GsonUtils.gson.fromJson(quoteXinYi.getActorData(), type);
                                // 填充数据
                                fillOther(map, updateReq.getData());
                                map.put(Constants.ACTOR_KOL_QUOTE_ID, String.valueOf(quoteXinYi.getId()));
                                workOrderDataResp.setStatus(Constants.WORK_ORDER_DATA_QUOTE);
                            } else {
                                workOrderDataResp.setStatus(Constants.WORK_ORDER_DATA_NEW);
                            }
                        }
                        if(Objects.nonNull(quoteWeiGe)) {
                            flag = screeningOther(quoteWeiGe.getActorData(), updateReq.getData());
                            if(flag) {
                                // 报价库库匹配成功
                                map = GsonUtils.gson.fromJson(quoteWeiGe.getActorData(), type);
                                // 填充数据
                                fillOther(map, updateReq.getData());
                                map.put(Constants.ACTOR_KOL_QUOTE_ID, String.valueOf(quoteWeiGe.getId()));
                                workOrderDataResp.setStatus(Constants.WORK_ORDER_DATA_QUOTE);
                            } else {
                                workOrderDataResp.setStatus(Constants.WORK_ORDER_DATA_NEW);
                            }
                        }
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
                map.put(Constants.ACTOR_KOL_PRICE_ID, String.valueOf(prices.getId()));
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
            String              media       = outboundMap.get(Constants.TITLE_MEDIA);
            String              address     = outboundMap.get(Constants.TITLE_RESOURCE_LOCATION);
            log.info(">>> media:{},address:{}", media, address);
            if(StringUtils.isBlank(media) || StringUtils.isBlank(address)) {
                log.warn(">>> 数据不完整,匹配失败:{}", outbound);
                return false;
            }
            // 按照达人平台筛选规则筛选
            String screenFields = PlatformRulesContainer.getScreenFields(media, address);
            if(StringUtils.isBlank(screenFields)) {
                log.info(">>> 筛选字段为空,匹配失败:{}", outbound);
                return false;
            }
            boolean  flag  = true;
            String[] split = StringUtils.split(screenFields, ";");
            for(String field : split) {
                if(!inboundMap.get(field).equals(outboundMap.get(field))) {
                    flag = false;
                    break;
                }
                log.info(">>> {}匹配成功...inbound:{},outbound:{}", field, inboundMap.get(field), outboundMap.get(field));
            }
            return flag;
        } catch(Exception e) {
            log.error(">>> 库内外信息数据匹配异常,inbound:{},outbound:{}", inbound, outbound, e);
            return false;
        }
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
        boolean             exitAskWorkOrderData = false;
        for(WorkOrderDataUpdateReq workOrderDataUpdateReq : req.getList()) {
            workOrderData = new WorkOrderData();
            workOrderData.setId(workOrderDataUpdateReq.getId());
            map = GsonUtils.gson.fromJson(workOrderDataUpdateReq.getData(), type);
            if(1 == workOrderDataUpdateReq.getAskType()) {
                if(Constants.WORK_ORDER_DATA_QUOTE.equals(workOrderDataUpdateReq.getStatus())) {
                    // 过滤报价库匹配数据,不进行询价操作
                    if(Objects.isNull(workOrderDataUpdateReq.getId())) {
                        workOrderDataNew = new WorkOrderData();
                        workOrderDataNew.setFieldsId(workOrderDataUpdateReq.getFieldsId());
                        workOrderDataNew.setProjectId(workOrderDataUpdateReq.getProjectId());
                        workOrderDataNew.setWorkOrderId(workOrderDataUpdateReq.getWorkOrderId());
                        workOrderDataNew.setStatus(workOrderDataUpdateReq.getStatus());
                        workOrderDataNew.setData(workOrderDataUpdateReq.getData());
                        workOrderDataNew.setCtime(DateUtil.date());
                        workOrderDataNew.setUtime(DateUtil.date());
                        workOrderDataListNew.add(workOrderDataNew);
                    } else {
                        workOrderData.setStatus(workOrderDataUpdateReq.getStatus());
                        workOrderData.setData(workOrderDataUpdateReq.getData());
                        workOrderData.setUtime(DateUtil.date());
                        workOrderData.setUpdateUserId(UserInfoContext.getUserId());
                        workOrderDataList.add(workOrderData);
                    }
                    continue;
                }
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
            exitAskWorkOrderData = true;
            workOrderData.setUtime(DateUtil.date());
            workOrderData.setUpdateUserId(UserInfoContext.getUserId());
            workOrderDataList.add(workOrderData);
        }

        Long xinyiId = 0L;
        Long weigeId = 0L;
        // 生成 询价/询档工单
        if(existXinYi) {
            xinyiId = createPointWorkOrder(workOrder, StartupRunner.SUPPLIER_USER_XIN_YI);
        }
        if(existWeiGe) {
            weigeId = createPointWorkOrder(workOrder, StartupRunner.SUPPLIER_USER_WEI_GE);
        }

        //为工单数据赋值父工单id
        if(xinyiId == 0 && weigeId == 0) {
            xinyiId = req.getWorkOrderId();
            weigeId = req.getWorkOrderId();
        }
        for(WorkOrderData orderData : workOrderDataList) {
            map = GsonUtils.gson.fromJson(orderData.getData(), type);
            if(Constants.SUPPLIER_XIN_YI.equals(map.get(Constants.SUPPLIER_FIELD))) {
                orderData.setWorkOrderId(xinyiId == 0 ? weigeId : xinyiId);
            } else {
                orderData.setWorkOrderId(weigeId == 0 ? xinyiId : weigeId);
            }
        }

        for(WorkOrderData data : workOrderDataListNew) {
            map = GsonUtils.gson.fromJson(data.getData(), type);
            if(Constants.SUPPLIER_XIN_YI.equals(map.get(Constants.SUPPLIER_FIELD))) {
                if(xinyiId != 0) {
                    data.setWorkOrderId(xinyiId);
                } else {
                    data.setWorkOrderId(weigeId);
                }
            } else {
                if(weigeId != 0) {
                    data.setWorkOrderId(weigeId);
                } else {
                    data.setWorkOrderId(xinyiId);
                }
            }
        }

        updateBatchById(workOrderDataList);
        if(!workOrderDataListNew.isEmpty()) {
            saveBatch(workOrderDataListNew);
        }

        // 更新需求工单状态
        if(exitAskWorkOrderData) {
            workOrder.setStatus(Constants.WORK_ORDER_ASK);
        } else {
            workOrder.setStatus(Constants.WORK_ORDER_QUOTE);
        }
        workOrder.setUpdateUserId(UserInfoContext.getUserId());
        workOrder.setUtime(DateUtil.date());
        workOrderDao.updateById(workOrder);
        return null;
    }

    private Long createPointWorkOrder(WorkOrder workOrder, Long userId) {
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
        return askWorkOrder.getId();
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
        WorkOrder workOrder = workOrderDao.selectById(req.getWorkOrderId());

        List<WorkOrder> workOrders = workOrderDao.selectList(new LambdaQueryWrapper<>(WorkOrder.class).eq(WorkOrder::getParentId, workOrder.getId()).eq(WorkOrder::getName, workOrder.getName()));
        //补供应商工单数据
        long xinyiId  = 0L;
        long weigeiId = 0L;
        if(workOrders.size() < 2) {
            for(WorkOrder order : workOrders) {
                //防止子工单错误创建
                order.setId(order.getParentId());
                if(order.getToUser() == StartupRunner.SUPPLIER_USER_XIN_YI) {
                    weigeiId = createPointWorkOrder(order, StartupRunner.SUPPLIER_USER_WEI_GE);
                } else {
                    xinyiId = createPointWorkOrder(order, StartupRunner.SUPPLIER_USER_XIN_YI);
                }
            }
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
            if(Constants.WORK_ORDER_EXPIRE_DEMAND != workOrder.getType()) {
                if("1".equals(map.get(Constants.ACTOR_INBOUND))) {
                    workOrderData.setStatus(Constants.WORK_ORDER_DATA_ASK_DATE);
                } else {
                    workOrderData.setStatus(Constants.WORK_ORDER_DATA_ASK_PRICE);
                }
            } else {
                workOrderData.setStatus(Constants.WORK_ORDER_DATA_ASK_PRICE);
            }
            if(Constants.SUPPLIER_XIN_YI.equals(map.get(Constants.SUPPLIER_FIELD)) && xinyiId != 0) {
                workOrderData.setWorkOrderId(xinyiId);
            }
            if(Constants.SUPPLIER_WEI_GE.equals(map.get(Constants.SUPPLIER_FIELD)) && weigeiId != 0) {
                workOrderData.setWorkOrderId(weigeiId);
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
        // 更新询价子工单状态
        if(existXinYi) {
            workOrder = new WorkOrder();
            workOrder.setType(Constants.WORK_ORDER_ENQUIRY_AGAIN);
            workOrder.setStatus(Constants.WORK_ORDER_ASK);
            workOrder.setUtime(DateUtil.date());
            workOrder.setUpdateUserId(UserInfoContext.getUserId());
            workOrderDao.update(workOrder, new LambdaUpdateWrapper<>(WorkOrder.class)
                    .eq(WorkOrder::getParentId, req.getWorkOrderId())
                    .eq(WorkOrder::getToUser, StartupRunner.SUPPLIER_USER_XIN_YI));
        } else {
            //修改对应父工单状态
            if(xinyiId != 0) {
                workOrder = new WorkOrder();
                workOrder.setId(xinyiId);
                workOrder.setStatus(Constants.WORK_ORDER_DATA_QUOTE);
                workOrderDao.updateById(workOrder);
            }
        }
        if(existWeiGe) {
            workOrder = new WorkOrder();
            workOrder.setType(Constants.WORK_ORDER_ENQUIRY_AGAIN);
            workOrder.setStatus(Constants.WORK_ORDER_ASK);
            workOrder.setUtime(DateUtil.date());
            workOrder.setUpdateUserId(UserInfoContext.getUserId());
            workOrderDao.update(workOrder, new LambdaUpdateWrapper<>(WorkOrder.class)
                    .eq(WorkOrder::getParentId, req.getWorkOrderId())
                    .eq(WorkOrder::getToUser, StartupRunner.SUPPLIER_USER_WEI_GE));
        } else {
            //修改对应父工单状态
            if(weigeiId != 0) {
                workOrder = new WorkOrder();
                workOrder.setId(weigeiId);
                workOrder.setStatus(Constants.WORK_ORDER_DATA_QUOTE);
                workOrderDao.updateById(workOrder);
            }
        }
        updateBatchById(list);
        // 更新工单状态
        workOrder = new WorkOrder();
        workOrder.setId(req.getWorkOrderId());
        workOrder.setStatus(Constants.WORK_ORDER_ASK);
        workOrder.setUtime(DateUtil.date());
        workOrder.setUpdateUserId(UserInfoContext.getUserId());
        workOrderDao.updateById(workOrder);
    }

    private void expire() {
        List<Prices> pricesList = pricesBiz.list(new LambdaQueryWrapper<>(Prices.class)
                                                         .eq(Prices::getIsReQuote, 0)
                                                         .eq(Prices::getPriceOnlyDay, "0")
                                                         .between(Prices::getInsureEndtime, new Date(), expireDate(new Date())));
        if(CollectionUtils.isEmpty(pricesList)) {
            throw new CheckException("重新询价数据不存在");
        }
        // 创建保价即将到期项目   (分别抖音快手和非抖音快手)
        String name = "保价即将到期";
//        String nameByTiktok    = "保价即将到期(抖音快手)";
//        String nameByNotTiktok = "保价即将到期(非抖音快手)";

        Project project = projectDao.selectOne(new LambdaQueryWrapper<>(Project.class).eq(Project::getName, name));
        if(Objects.isNull(project)) {
            project = new Project();
            project.setName(name);
            project.setDescs("保价即将到期项目");
            project.setCtime(new Date());
            project.setUtime(new Date());
            projectDao.insert(project);
        }
        log.info(">>> 保价即将到期项目已生成...");

        // 生成保价到期询价工单
        WorkOrder workOrderByTiktok = new WorkOrder();
        workOrderByTiktok.setProjectId(project.getId());
        workOrderByTiktok.setProjectName(name);

        WorkOrder workOrderByNotTiktok = new WorkOrder();
        workOrderByNotTiktok.setProjectId(project.getId());
        workOrderByNotTiktok.setProjectName(name);


        boolean falgByTiktok    = false;
        boolean falgByNotTiktok = false;
        boolean falg            = false;

        long xinyiTiktok = 0, xinyiNotTiktok = 0, weigeTiktok = 0, weigeNotTiktok = 0;

        // 生成工单数据及提醒消息
        List<WorkOrderData> workOrderDataList = new ArrayList<>();
        WorkOrderData       workOrderDataXinYi;
        WorkOrderData       workOrderDataWeiGe;
        Type type = new TypeToken<Map<String, String>>() {
        }.getType();
        Map<String, String> map;
        String              compareFlag;
        List<Prices>        isReQuoteList = new ArrayList<>();
        Prices              quote;
        for(Prices prices : pricesList) {
            //修改重新询价标识
            quote = new Prices();
            quote.setIsReQuote(1);
            quote.setId(prices.getId());
            isReQuoteList.add(quote);

            workOrderDataXinYi = new WorkOrderData();
            workOrderDataXinYi.setFieldsId(1L);
            workOrderDataXinYi.setProjectId(project.getId());
            workOrderDataXinYi.setStatus(Constants.WORK_ORDER_DATA_ASK_PRICE);
            map = GsonUtils.gson.fromJson(prices.getActorData(), type);
            map.put(Constants.ACTOR_DATA_SN, prices.getActorSn());
            map.put(Constants.ACTOR_KOL_PRICE_ID, String.valueOf(prices.getId()));
            map.put(Constants.SUPPLIER_FIELD, Constants.SUPPLIER_XIN_YI);
            map.put(Constants.ACTOR_INBOUND, "0");
            //区别是否 抖音快手， 非抖音快手
            String media = map.get(Constants.TITLE_MEDIA);
            if(media == null)
                continue;
            workOrderDataWeiGe = new WorkOrderData();

            if("抖音".equals(media) || "快手".equals(media)) {
                if(!falgByTiktok) {
                    WorkOrder workOrderWeiGe = new WorkOrder();
                    workOrderWeiGe.setType(Constants.WORK_ORDER_ENQUIRY);
                    workOrderWeiGe.setStatus(Constants.WORK_ORDER_ASK);
                    workOrderWeiGe.setToUser(StartupRunner.SUPPLIER_USER_WEI_GE);
                    workOrderWeiGe.setCtime(DateUtil.date());
                    workOrderWeiGe.setUtime(DateUtil.date());

                    WorkOrder workOrderXinYi = new WorkOrder();
                    workOrderXinYi.setType(Constants.WORK_ORDER_ENQUIRY);
                    workOrderXinYi.setStatus(Constants.WORK_ORDER_ASK);
                    workOrderXinYi.setToUser(StartupRunner.SUPPLIER_USER_XIN_YI);
                    workOrderXinYi.setCtime(DateUtil.date());
                    workOrderXinYi.setUtime(DateUtil.date());

                    workOrderByTiktok.setProjectName(name + "（抖音快手）");
                    createWorkOrder(workOrderByTiktok);
                    log.info(">>> 保价即将到期工单（抖音快手）已生成...");
                    falgByTiktok = true;
                    if(!StringUtils.isBlank(workOrderByTiktok.getOrderSn())) {
                        workOrderXinYi.setOrderSn(workOrderByTiktok.getOrderSn());
                        workOrderXinYi.setName(workOrderByTiktok.getName());
                        workOrderXinYi.setProjectId(workOrderByTiktok.getProjectId());
                        workOrderXinYi.setProjectName(workOrderByTiktok.getProjectName());
                        workOrderXinYi.setParentId(workOrderByTiktok.getId());
                        workOrderDao.insert(workOrderXinYi);

                        xinyiTiktok = workOrderXinYi.getId();

                        workOrderWeiGe.setOrderSn(workOrderByTiktok.getOrderSn());
                        workOrderWeiGe.setName(workOrderByTiktok.getName());
                        workOrderWeiGe.setProjectId(workOrderByTiktok.getProjectId());
                        workOrderWeiGe.setProjectName(workOrderByTiktok.getProjectName());
                        workOrderWeiGe.setParentId(workOrderByTiktok.getId());
                        workOrderDao.insert(workOrderWeiGe);
                        weigeTiktok = workOrderWeiGe.getId();
                        log.info(">>> 保价即将到期新意,微格 询价子工单(抖音快手)已生成...");
                    }
                }
            } else {
                if(!falgByNotTiktok) {
                    workOrderByNotTiktok.setProjectName(name + "（非抖音快手）");
                    createWorkOrder(workOrderByNotTiktok);
                    log.info(">>> 保价即将到期工单（非抖音快手）已生成...");
                    falgByNotTiktok = true;

                    WorkOrder workOrderWeiGe = new WorkOrder();
                    workOrderWeiGe.setType(Constants.WORK_ORDER_ENQUIRY);
                    workOrderWeiGe.setStatus(Constants.WORK_ORDER_ASK);
                    workOrderWeiGe.setToUser(StartupRunner.SUPPLIER_USER_WEI_GE);
                    workOrderWeiGe.setCtime(DateUtil.date());
                    workOrderWeiGe.setUtime(DateUtil.date());

                    WorkOrder workOrderXinYi = new WorkOrder();
                    workOrderXinYi.setType(Constants.WORK_ORDER_ENQUIRY);
                    workOrderXinYi.setStatus(Constants.WORK_ORDER_ASK);
                    workOrderXinYi.setToUser(StartupRunner.SUPPLIER_USER_XIN_YI);
                    workOrderXinYi.setCtime(DateUtil.date());
                    workOrderXinYi.setUtime(DateUtil.date());

                    if(!StringUtils.isBlank(workOrderByNotTiktok.getOrderSn())) {
                        workOrderXinYi.setOrderSn(workOrderByNotTiktok.getOrderSn());
                        workOrderXinYi.setName(workOrderByNotTiktok.getName());
                        workOrderXinYi.setProjectId(workOrderByNotTiktok.getProjectId());
                        workOrderXinYi.setProjectName(workOrderByNotTiktok.getProjectName());
                        workOrderXinYi.setParentId(workOrderByNotTiktok.getId());
                        workOrderDao.insert(workOrderXinYi);

                        xinyiNotTiktok = workOrderXinYi.getId();

                        workOrderWeiGe.setOrderSn(workOrderByNotTiktok.getOrderSn());
                        workOrderWeiGe.setName(workOrderByNotTiktok.getName());
                        workOrderWeiGe.setProjectId(workOrderByNotTiktok.getProjectId());
                        workOrderWeiGe.setProjectName(workOrderByNotTiktok.getProjectName());
                        workOrderWeiGe.setParentId(workOrderByNotTiktok.getId());
                        workOrderDao.insert(workOrderWeiGe);
                        weigeNotTiktok = workOrderWeiGe.getId();
                        log.info(">>> 保价即将到期新意,微格 询价子工单(非抖音快手)已生成...");
                    }
                }
            }

            if("抖音".equals(media) || "快手".equals(media)) {
                workOrderDataXinYi.setWorkOrderId(xinyiTiktok);
                workOrderDataWeiGe.setWorkOrderId(weigeTiktok);
            } else {
                workOrderDataXinYi.setWorkOrderId(xinyiNotTiktok);
                workOrderDataWeiGe.setWorkOrderId(weigeNotTiktok);
            }


            compareFlag = StringUtils.join(media,
                                           map.get(Constants.TITLE_ID_OR_LINK),
                                           map.get(Constants.TITLE_RESOURCE_LOCATION));
            map.put(Constants.ACTOR_COMPARE_FLAG, MD5Util.getMD5(compareFlag));
            workOrderDataXinYi.setData(GsonUtils.gson.toJson(map));
            workOrderDataXinYi.setCtime(DateUtil.date());
            workOrderDataXinYi.setUtime(DateUtil.date());
            workOrderDataXinYi.setPriceOnlyDay("0");
            workOrderDataXinYi.setIsDelete("0");
            workOrderDataList.add(workOrderDataXinYi);


            workOrderDataWeiGe.setFieldsId(1L);
            workOrderDataWeiGe.setProjectId(project.getId());

            workOrderDataWeiGe.setStatus(Constants.WORK_ORDER_DATA_ASK_PRICE);
            map = GsonUtils.gson.fromJson(prices.getActorData(), type);
            map.put(Constants.ACTOR_DATA_SN, prices.getActorSn());
            map.put(Constants.ACTOR_KOL_PRICE_ID, String.valueOf(prices.getId()));
            map.put(Constants.SUPPLIER_FIELD, Constants.SUPPLIER_WEI_GE);
            map.put(Constants.ACTOR_INBOUND, "0");
            map.put(Constants.ACTOR_COMPARE_FLAG, MD5Util.getMD5(compareFlag));
            workOrderDataWeiGe.setData(GsonUtils.gson.toJson(map));
            workOrderDataWeiGe.setCtime(DateUtil.date());
            workOrderDataWeiGe.setUtime(DateUtil.date());
            workOrderDataWeiGe.setPriceOnlyDay("0");
            workOrderDataWeiGe.setIsDelete("0");
            workOrderDataList.add(workOrderDataWeiGe);
        }
//        saveBatch(workOrderDataList,workOrderDataList.size());
        threadPool.execute(() -> {
            workOrderDataDao.insertBatch(workOrderDataList);
        });
        //异步会导致消息延迟，无法及时刷新
        pricesBiz.updateBatchById(isReQuoteList);

    }

    private void createWorkOrder(WorkOrder workOrder) {
        Integer count = workOrderDao.getCount(workOrder.getProjectId());
        count = count + 1;
        workOrder.setName("第" + count + "批次");
        workOrder.setOrderSn(DateUtil.format(DateUtil.date(), DatePattern.PURE_DATETIME_MS_PATTERN));
        workOrder.setType(Constants.WORK_ORDER_EXPIRE_DEMAND);
        workOrder.setStatus(Constants.WORK_ORDER_ASK);
        workOrder.setCtime(DateUtil.date());
        workOrder.setUtime(DateUtil.date());
        workOrder.setCreateUserId(UserInfoContext.getUserId());
        workOrder.setUpdateUserId(UserInfoContext.getUserId());
        //
        workOrderDao.insert(workOrder);
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
            Map<String, String> dataMap = GsonUtils.gson.fromJson(workOrderDataUpdateReq.getData(),
                                                                  new com.google.common.reflect.TypeToken<Map<String, String>>() {
                                                                  }.getType());
            workOrderData.setPriceOnlyDay((dataMap == null || dataMap.get("priceOnlyDay") == null || "否".equals(dataMap.get("priceOnlyDay"))) ? "0" : "1");
            workOrderDataList.add(workOrderData);
        }
        updateBatchById(workOrderDataList);
        // 更新询价工单状态
        WorkOrder workOrder = workOrderDao.selectById(req.getWorkOrderId());
        workOrder.setStatus(Constants.WORK_ORDER_QUOTE);
        workOrder.setUpdateUserId(UserInfoContext.getUserId());
        workOrder.setUtime(DateUtil.date());
        workOrderDao.updateById(workOrder);

        //根据parentId查找对应子工单
        List<WorkOrder> workOrderList = workOrderDao.selectList(new LambdaQueryWrapper<>(WorkOrder.class).eq(WorkOrder::getParentId, workOrder.getParentId()));

        // 更新父工单状态
        LambdaQueryWrapper<WorkOrderData> wrapper = Wrappers.lambdaQuery(WorkOrderData.class);
//        wrapper.eq(WorkOrderData::getWorkOrderId, workOrder.getParentId())
        wrapper.in(WorkOrderData::getWorkOrderId, workOrderList.stream().map(WorkOrder::getId).collect(Collectors.toList()))
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

        List<WorkOrder> workOrders = workOrderDao.selectList(new LambdaQueryWrapper<>(WorkOrder.class).eq(WorkOrder::getParentId, req.getWorkOrderId()));

        List<WorkOrderData> list = null;

        //如果条件成立则说明父工单没有生成子工单
        if(workOrders == null || workOrders.size() == 0) {
            list = list(new LambdaQueryWrapper<>(WorkOrderData.class)
                                .eq(WorkOrderData::getWorkOrderId, req.getWorkOrderId())
                                .eq(WorkOrderData::getStatus, Constants.WORK_ORDER_DATA_QUOTE));
        } else {
            list = list(new LambdaQueryWrapper<>(WorkOrderData.class)
                                .in(WorkOrderData::getWorkOrderId, workOrders.stream().map(WorkOrder::getId).collect(Collectors.toList()))
                                .eq(WorkOrderData::getStatus, Constants.WORK_ORDER_DATA_QUOTE));
        }

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
    @Transactional
    public Long order(WorkOrderDataOrderReq req) {
        if(CollectionUtils.isEmpty(req.getWorkOrderDataIds())) {
            throw new CheckException("下单工单数据不能为空");
        }

        WorkOrder workOrder = workOrderDao.selectById(req.getWorkOrderId());

        if(workOrder == null || StringUtils.isBlank(workOrder.getPdfPath())) {
            throw new CheckException("无法下单，请检查工单状态或报备图是否上传!");
        }

        List<WorkOrderData> list = list(new LambdaQueryWrapper<>(WorkOrderData.class)
                                                .in(WorkOrderData::getId, req.getWorkOrderDataIds()));
        if(CollectionUtils.isEmpty(list)) {
            throw new CheckException("工单数据不存在");
        }

        Type type = new TypeToken<Map<String, String>>() {
        }.getType();
        List<WorkOrderData> workOrderDataList = new ArrayList<>();
        List<Prices>        pricesList        = new ArrayList<>();
        List<PricesLogs>    pricesLogsList    = new ArrayList<>();
        List<Quote>         quoteList         = new ArrayList<>();
        Map<String, String> map;
        String              inbound;
        String              actorSn;
        Prices              prices;
        PricesLogs          pricesLogs;
        Quote               quote;

        // 合同到期时间处理
        Date xinyiTime = null;
        Date weigeTime = null;

        User xinyi = userBiz.getOne(new LambdaQueryWrapper<>(User.class).eq(User::getName, "xinyi"));
        if(xinyi != null && xinyi.getContractTime() != null) {
            xinyiTime = xinyi.getContractTime();
        }
        User weige = userBiz.getOne(new LambdaQueryWrapper<>(User.class).eq(User::getName, "weige"));
        if(weige != null && weige.getContractTime() != null) {
            weigeTime = weige.getContractTime();
        }

        for(WorkOrderData workOrderData : list) {
            // 更新工单数据
            workOrderData.setStatus(Constants.WORK_ORDER_DATA_REVIEW_PASS);
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
                // 移除档期开始时间和档期结束时间
                map.remove(Constants.ACTOR_SCHEDULE_START_TIME);
                map.remove(Constants.ACTOR_SCHEDULE_END_TIME);
                //
                prices.setActorData(GsonUtils.gson.toJson(map));
                prices.setCommission(StringUtils.isNotBlank(map.get(Constants.ACTOR_COMMISSION)) ?
                                     Integer.parseInt(map.get(Constants.ACTOR_COMMISSION)) : null);
                prices.setPrice(StringUtils.isNotBlank(map.get(Constants.ACTOR_PRICE)) ?
                                Double.parseDouble(map.get(Constants.ACTOR_PRICE)) : null);

                String provider = map.get(Constants.ACTOR_PROVIDER);
                prices.setProvider(provider);

                //合同过期时间
                Date contractTime = null;

                Date insureEndtime = null;

                if(Constants.SUPPLIER_XIN_YI.equals(provider)) {
                    contractTime = xinyiTime;
                } else if(Constants.SUPPLIER_WEI_GE.equals(provider)) {
                    contractTime = weigeTime;
                } else {

                }
                if(contractTime != null) {
                    //报价一天
                    if("1".equals(workOrderData.getPriceOnlyDay())) {
                        insureEndtime = DateUtil.endOfDay(new Date());
                    } else {
                        if(contractTime.compareTo(DateUtil.offsetMonth(DateUtil.date(), 6)) < 0) {
                            insureEndtime = DateUtil.offsetMonth(contractTime, 0);
                        } else {
                            insureEndtime = DateUtil.offsetMonth(DateUtil.date(), 6);
                        }
                    }
                } else {
                    if("1".equals(workOrderData.getPriceOnlyDay())) {
                        insureEndtime = DateUtil.endOfDay(new Date());
                    } else {
                        insureEndtime = DateUtil.offsetMonth(DateUtil.date(), 6);
                    }
                }

                prices.setInsureEndtime(insureEndtime);

                log.info("供应商：{},合同到期时间：{}，预计报价到期时间：{}，实际报价到期时间：{}", provider, contractTime, DateUtil.offsetMonth(DateUtil.date(), 6), prices.getInsureEndtime());
                prices.setCtime(DateUtil.date());
                prices.setUtime(DateUtil.date());
                prices.setCreateUserId(UserInfoContext.getUserId());
                prices.setUpdateUserId(UserInfoContext.getUserId());
                prices.setPriceOnlyDay(workOrderData.getPriceOnlyDay());

                //KOL资源库有则替换，没有则新增
                List<Prices> pricesInKOL = pricesBiz.getListActorSn(actorSn);

                if(pricesInKOL != null && pricesInKOL.size() > 0) {
                    for(Prices p : pricesInKOL) {
                        if(screeningOther(p.getActorData(), prices.getActorData())) {
                            prices.setId(p.getId());
                            prices.setIsReQuote(0);
                            break;
                        }
                    }
                }
                pricesList.add(prices);
            }
            // 报价记录表
            pricesLogs = new PricesLogs();
            pricesLogs.setActorSn(actorSn);
            pricesLogs.setActorData(workOrderData.getData());
            if("1".equals(inbound)) {
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

        // 处理未勾选的报价数据状态(过滤直接审核通过的数据) 新增至报价表
        List<WorkOrder> orderList = workOrderDao.selectList(new LambdaQueryWrapper<>(WorkOrder.class).eq(WorkOrder::getParentId, req.getWorkOrderId()));
        List<WorkOrderData> list2 = list(new LambdaQueryWrapper<>(WorkOrderData.class).in(WorkOrderData::getWorkOrderId, orderList.stream().map(WorkOrder::getId).collect(Collectors.toList()))
                                                 .notIn(WorkOrderData::getStatus, Constants.WORK_ORDER_DATA_REVIEW, Constants.WORK_ORDER_DATA_REVIEW_PASS));
        for(WorkOrderData unSelect : list2) {
            map = GsonUtils.gson.fromJson(unSelect.getData(), type);
            if("0".equals(map.get(Constants.ACTOR_INBOUND))) {
                //报价库
                quote = new Quote();
                quote.setProjectId(req.getProjectId());
                quote.setActorSn(map.get(Constants.ACTOR_DATA_SN));
                quote.setActorData(unSelect.getData());
                quote.setCommission(StringUtils.isNotBlank(map.get(Constants.ACTOR_COMMISSION)) ?
                                    (!"不涉及".equals(map.get(Constants.ACTOR_COMMISSION)) ? Integer.parseInt(map.get(Constants.ACTOR_COMMISSION)) : 0) : null);
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
        }

        if(!CollectionUtils.isEmpty(pricesList)) {
            threadPool.execute(() -> {
                pricesBiz.saveOrUpdateBatch(pricesList, pricesList.size());
            });
        }
        if(!CollectionUtils.isEmpty(pricesLogsList)) {
            pricesLogsBiz.saveBatch(pricesLogsList, pricesLogsList.size());
        }
        if(!CollectionUtils.isEmpty(quoteList)) {
            threadPool.execute(() -> {
                quoteBiz.batchSaveOrUpdate(quoteList);
            });
        }
        // 更新工单状态 -> 已下单
        workOrder.setStatus(Constants.WORK_ORDER_ORDER);
        workOrder.setUtime(DateUtil.date());
        workOrder.setUpdateUserId(UserInfoContext.getUserId());
        workOrderDao.updateById(workOrder);
        return null;
    }

//    @Override
//    public Long order(WorkOrderDataOrderReq req) {
//        if(CollectionUtils.isEmpty(req.getWorkOrderDataIds())) {
//            throw new CheckException("提审工单数据不能为空");
//        }
//        /**
//         * 需求工单直接提审
//         * 保价到期订单，媒介选择的达人信息不需要提审，未选择的数据直接入报价库
//         */
//        WorkOrder workOrder = workOrderDao.selectById(req.getWorkOrderId());
//        // 更新工单数据状态
//        List<Long>          actorData         = new ArrayList<>();
//        List<WorkOrderData> workOrderDataList = new ArrayList<>();
//        WorkOrderData       workOrderData;
//        for(Long workOrderDataId : req.getWorkOrderDataIds()) {
//            workOrderData = new WorkOrderData();
//            workOrderData.setId(workOrderDataId);
//            if(Constants.WORK_ORDER_EXPIRE_DEMAND != workOrder.getType()) {
//                workOrderData.setStatus(Constants.WORK_ORDER_DATA_REVIEW);
//            } else {
//                // 保价到期工单数据
//                workOrderData.setStatus(Constants.WORK_ORDER_DATA_REVIEW_PASS);
//                actorData.add(workOrderDataId);
//            }
//            workOrderData.setUtime(DateUtil.date());
//            workOrderData.setUpdateUserId(UserInfoContext.getUserId());
//            workOrderDataList.add(workOrderData);
//        }
//        updateBatchById(workOrderDataList);
//        //
//        if(!CollectionUtils.isEmpty(actorData)) {
//            List<WorkOrderData> list = list(new LambdaQueryWrapper<>(WorkOrderData.class)
//                                                    .eq(WorkOrderData::getWorkOrderId, req.getWorkOrderId())
//                                                    .in(WorkOrderData::getId, actorData));
//            updatePrice(list);
//        }
//        // 处理未勾选的报价数据状态(过滤直接审核通过的数据) 新增至报价表
//        List<WorkOrderData> list = list(new LambdaQueryWrapper<>(WorkOrderData.class).eq(WorkOrderData::getWorkOrderId, req.getWorkOrderId())
//                                                .notIn(WorkOrderData::getStatus, Constants.WORK_ORDER_DATA_REVIEW, Constants.WORK_ORDER_DATA_REVIEW_PASS));
//        if(!CollectionUtils.isEmpty(list)) {
//            workOrderDataList.clear();
//            List<Quote> quoteList = new ArrayList<>();
//            Quote       quote;
//            Type type = new TypeToken<Map<String, String>>() {
//            }.getType();
//            Map<String, String> map;
//            for(WorkOrderData unSelect : list) {
//                unSelect.setStatus(Constants.WORK_ORDER_DATA_QUOTE_UNSELECTED);
//                unSelect.setUtime(DateUtil.date());
//                unSelect.setUpdateUserId(UserInfoContext.getUserId());
////                unSelect.setPriceOnlyDay();
//                workOrderDataList.add(unSelect);
//
//                map = GsonUtils.gson.fromJson(unSelect.getData(), type);
//                if("0".equals(map.get(Constants.ACTOR_INBOUND))) {
//                    quote = new Quote();
//                    quote.setProjectId(req.getProjectId());
//                    quote.setActorSn(map.get(Constants.ACTOR_DATA_SN));
//                    quote.setActorData(unSelect.getData());
//                    quote.setCommission(StringUtils.isNotBlank(map.get(Constants.ACTOR_COMMISSION)) ?
//                                        Integer.parseInt(map.get(Constants.ACTOR_COMMISSION)) : null);
//                    quote.setPrice(StringUtils.isNotBlank(map.get(Constants.ACTOR_PRICE)) ?
//                                   Double.parseDouble(map.get(Constants.ACTOR_PRICE)) : null);
//                    quote.setProvider(map.get(Constants.ACTOR_PROVIDER));
//                    // 保价到期时间14天后
//                    quote.setInsureEndtime(DateUtil.offsetDay(DateUtil.date(), 14));
//                    quote.setEnableFlag(1);
//                    quote.setCtime(DateUtil.date());
//                    quote.setUtime(DateUtil.date());
//                    quote.setCreateUserId(UserInfoContext.getUserId());
//                    quote.setUpdateUserId(UserInfoContext.getUserId());
//                    quoteList.add(quote);
//                }
//            }
//            updateBatchById(workOrderDataList);
//            quoteBiz.batchSaveOrUpdate(quoteList);
//        }
//        // 更新工单状态为 审核中
//        if(Constants.WORK_ORDER_EXPIRE_DEMAND != workOrder.getType()) {
//            workOrder.setStatus(Constants.WORK_ORDER_REVIEW);
//        } else {
//            // 保价到期工单 直接下单
//            workOrder.setStatus(Constants.WORK_ORDER_ORDER);
//        }
//        workOrder.setUpdateUserId(UserInfoContext.getUserId());
//        workOrder.setUtime(DateUtil.date());
//        workOrderDao.updateById(workOrder);
//        return null;
//    }

    private void updatePrice(List<WorkOrderData> list) {
        /**
         * 重新入库，新价格在老价格到期后生效展示
         */
        log.info(">>> kol资源数据到期询价后重新入库...size:{}", list.size());
        Type type = new TypeToken<Map<String, String>>() {
        }.getType();
        Map<String, String> map;
        List<Prices>        pricesList = new ArrayList<>();
        Prices              prices;
        for(WorkOrderData workOrderData : list) {
            prices = new Prices();
            map = GsonUtils.gson.fromJson(workOrderData.getData(), type);
            map.remove(Constants.ACTOR_SCHEDULE_START_TIME);
            map.remove(Constants.ACTOR_SCHEDULE_END_TIME);
            prices.setId(Long.parseLong(map.get(Constants.ACTOR_KOL_PRICE_ID)));
            prices.setActorData(GsonUtils.gson.toJson(map));
            prices.setCommission(StringUtils.isNotBlank(map.get(Constants.ACTOR_COMMISSION)) ?
                                 Integer.parseInt(map.get(Constants.ACTOR_COMMISSION)) : null);
            prices.setPrice(StringUtils.isNotBlank(map.get(Constants.ACTOR_PRICE)) ?
                            Double.parseDouble(map.get(Constants.ACTOR_PRICE)) : null);
            prices.setProvider(map.get(Constants.ACTOR_PROVIDER));
            prices.setInsureEndtime(DateUtil.offsetMonth(DateUtil.date(), 6));
            prices.setIsReQuote(0);
            prices.setUtime(DateUtil.date());
            prices.setUpdateUserId(UserInfoContext.getUserId());
            pricesList.add(prices);
        }
        if(!CollectionUtils.isEmpty(pricesList)) {
            pricesBiz.updateBatchById(pricesList);
        }
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

        // 合同到期时间处理
        Date xinyiTime = null;
        Date weigeTime = null;

        User xinyi = userBiz.getOne(new LambdaQueryWrapper<>(User.class).eq(User::getName, "xinyi"));
        if(xinyi != null && xinyi.getContractTime() != null) {
            xinyiTime = xinyi.getContractTime();
        }
        User weige = userBiz.getOne(new LambdaQueryWrapper<>(User.class).eq(User::getName, "weige"));
        if(weige != null && weige.getContractTime() != null) {
            weigeTime = weige.getContractTime();
        }

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
                // 移除档期开始时间和档期结束时间
                map.remove(Constants.ACTOR_SCHEDULE_START_TIME);
                map.remove(Constants.ACTOR_SCHEDULE_END_TIME);
                //
                prices.setActorData(GsonUtils.gson.toJson(map));
                prices.setCommission(StringUtils.isNotBlank(map.get(Constants.ACTOR_COMMISSION)) ?
                                     Integer.parseInt(map.get(Constants.ACTOR_COMMISSION)) : null);
                prices.setPrice(StringUtils.isNotBlank(map.get(Constants.ACTOR_PRICE)) ?
                                Double.parseDouble(map.get(Constants.ACTOR_PRICE)) : null);

                String provider = map.get(Constants.ACTOR_PROVIDER);
                prices.setProvider(provider);

                //合同过期时间
                Date contractTime = null;

                Date insureEndtime = null;

                if(Constants.SUPPLIER_XIN_YI.equals(provider)) {
                    contractTime = xinyiTime;
                } else if(Constants.SUPPLIER_WEI_GE.equals(provider)) {
                    contractTime = weigeTime;
                } else {

                }
                if(contractTime != null) {
                    //报价一天
                    if("1".equals(workOrderData.getPriceOnlyDay())) {
                        insureEndtime = DateUtil.endOfDay(new Date());
                    } else {
                        if(contractTime.compareTo(DateUtil.offsetMonth(DateUtil.date(), 6)) < 0) {
                            insureEndtime = DateUtil.offsetMonth(contractTime, 0);
                        } else {
                            insureEndtime = DateUtil.offsetMonth(DateUtil.date(), 6);
                        }
                    }
                } else {
                    if("1".equals(workOrderData.getPriceOnlyDay())) {
                        insureEndtime = DateUtil.endOfDay(new Date());
                    } else {
                        insureEndtime = DateUtil.offsetMonth(DateUtil.date(), 6);
                    }
                }

                prices.setInsureEndtime(insureEndtime);

                log.info("供应商：{},合同到期时间：{}，预计报价到期时间：{}，实际报价到期时间：{}", provider, contractTime, DateUtil.offsetMonth(DateUtil.date(), 6), prices.getInsureEndtime());
                prices.setCtime(DateUtil.date());
                prices.setUtime(DateUtil.date());
                prices.setCreateUserId(UserInfoContext.getUserId());
                prices.setUpdateUserId(UserInfoContext.getUserId());
                prices.setPriceOnlyDay(workOrderData.getPriceOnlyDay());
                pricesList.add(prices);
            }
            // 报价记录表
            pricesLogs = new PricesLogs();
            pricesLogs.setActorSn(actorSn);
            pricesLogs.setActorData(workOrderData.getData());
            if("1".equals(inbound)) {
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
            quoteBiz.batchSaveOrUpdate(quoteList);
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

        LambdaQueryWrapper<WorkOrderData> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkOrderData::getWorkOrderId, req.getWorkOrderId());
        List<WorkOrderData> orderData = baseMapper.selectList(wrapper);
        workOrderDataTemplateExport(orderData, response, "库内外筛选-" + DateTimeUtils.getDate("yyyy-MM-dd"), req.getTemplateType(), null);

//        Fields fields = fieldsBiz.getById(Constants.FIELD_TYPE_DEMAND);
//        //获取字段列表
//        List<FieldsBo> fieldsBos = GsonUtils.gson.fromJson(fields.getFieldList(), new TypeToken<ArrayList<FieldsBo>>() {
//        }.getType());
//
//        List<List<List<String>>> allData = new ArrayList<>();
//        //库外数据
//        List<List<String>> outboundData = new ArrayList<>();
//
//        //新意
//        List<List<String>> xinyiData = new ArrayList<>();
//
//        //维格
//        List<List<String>> weigeData = new ArrayList<>();
//
//        List<FieldsBo> newList = fieldsBos.stream().filter(x -> x.isEffect()).collect(Collectors.toList());
//        //获取中文表头
//        List<String> titleCN = newList.stream().map(FieldsBo::getTitle).collect(Collectors.toList());
//
//        outboundData.add(titleCN);
//        xinyiData.add(titleCN);
//        weigeData.add(titleCN);
//
//        LambdaQueryWrapper<WorkOrderData> wrapper = new LambdaQueryWrapper<>();
//        wrapper.eq(WorkOrderData::getWorkOrderId, req.getWorkOrderId());
//        List<WorkOrderData> orderData = baseMapper.selectList(wrapper);
//
//        for(int i = 0; i < orderData.size(); i++) {
//            WorkOrderData           workOrderData = orderData.get(i);
//            List<String>            data          = new ArrayList<>();
//            HashMap<String, String> hashMap       = GsonUtils.gson.fromJson(workOrderData.getData(), HashMap.class);
//            if("0".equals(hashMap.get(Constants.ACTOR_INBOUND))) {
//                pricesBiz.addExportData(outboundData, data, hashMap, newList);
//            } else {
//                String supplier = hashMap.get(Constants.SUPPLIER_FIELD);
//                if(Constants.SUPPLIER_XIN_YI.equalsIgnoreCase(supplier)) {
//                    pricesBiz.addExportData(xinyiData, data, hashMap, newList);
//                } else if(Constants.SUPPLIER_WEI_GE.equalsIgnoreCase(supplier)) {
//                    pricesBiz.addExportData(weigeData, data, hashMap, newList);
//                }
//            }
//        }
//        allData.add(outboundData);
//        allData.add(xinyiData);
//        allData.add(weigeData);
//
//        List<String> sheetNames = Arrays.asList("库外数据", Constants.SUPPLIER_XIN_YI, Constants.SUPPLIER_WEI_GE);
//        try {
//            EasyExcelUtil.writeExcelSheet(response, allData, "需求单详情", sheetNames);
//        } catch(
//                Exception e) {
//            e.printStackTrace();
//        }

    }

    @Override
    public void supplierExport(WorkOrderBatchUpdateReq req, HttpServletResponse response) {

        List<WorkOrder> workOrders = workOrderDao.selectList(new LambdaQueryWrapper<>(WorkOrder.class).eq(WorkOrder::getParentId, req.getWorkOrderId()));

        LambdaQueryWrapper<WorkOrderData> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(WorkOrderData::getWorkOrderId, workOrders.stream().map(WorkOrder::getId).collect(Collectors.toList()))
                .in(WorkOrderData::getStatus, Constants.WORK_ORDER_DATA_ASK_DATE, Constants.WORK_ORDER_DATA_ASK_PRICE);
        if(StartupRunner.SUPPLIER_USER_XIN_YI == UserInfoContext.getUserId()) {
            // 新意
            wrapper.apply("JSON_UNQUOTE(JSON_EXTRACT(data,\"$.supplier\")) = {0}", Constants.SUPPLIER_XIN_YI);
        } else {
            wrapper.apply("JSON_UNQUOTE(JSON_EXTRACT(data,\"$.supplier\")) = {0}", Constants.SUPPLIER_WEI_GE);
        }
        List<WorkOrderData> orderData = baseMapper.selectList(wrapper);
        workOrderDataTemplateExport(orderData, response, "供应商报价-" + DateTimeUtils.getDate("yyyy-MM-dd"), req.getTemplateType(), "");

//
//        Fields fields = fieldsBiz.getById(Constants.FIELD_TYPE_QUOTE);
//        //获取字段列表
//        List<FieldsBo> fieldsBos = GsonUtils.gson.fromJson(fields.getFieldList(), new TypeToken<ArrayList<FieldsBo>>() {
//        }.getType());
//
//        List<List<List<String>>> allData = new ArrayList<>();
//        //报价&排期
//        List<List<String>> outboundData = new ArrayList<>();
//        //仅排档期
//        List<List<String>> inboundData = new ArrayList<>();
//
//        List<FieldsBo> newList = fieldsBos.stream().filter(x -> x.isEffect()).collect(Collectors.toList());
//        //获取中文表头
//        List<String> titleCN = newList.stream().map(FieldsBo::getTitle).collect(Collectors.toList());
//
//        outboundData.add(titleCN);
//        inboundData.add(titleCN);
//
//        String[] split = req.getWorkerOrderDataIds().split(",");
//        for(int i = 0; i < split.length; i++) {
//            List<String>            data          = new ArrayList<>();
//            String                  id            = split[i];
//            WorkOrderData           workOrderData = getById(id);
//            HashMap<String, String> hashMap       = GsonUtils.gson.fromJson(workOrderData.getData(), HashMap.class);
//            if("1".equals(hashMap.get(Constants.ACTOR_INBOUND))) {
//                pricesBiz.addExportData(inboundData, data, hashMap, newList);
//            } else {
//                pricesBiz.addExportData(outboundData, data, hashMap, newList);
//            }
//        }
//
//        allData.add(outboundData);
//        allData.add(inboundData);
//
//
////        List<WorkOrderDataUpdateReq> list = req.getList();
////        if(list != null) {
////            for(WorkOrderDataUpdateReq orderDataUpdateReq : list) {
////                List<String> data = new ArrayList<>();
////                //库内 排期
////                if(orderDataUpdateReq.getInbound() == 1) {
////                    pricesBiz.addExportData(inboundData, data, orderDataUpdateReq.getData(), newList);
////                }
////                pricesBiz.addExportData(outboundData, data, orderDataUpdateReq.getData(), newList);
////            }
////            allData.add(outboundData);
////            allData.add(inboundData);
////        }
//        List<String> sheetNames = Arrays.asList("报价&排期", "仅排档期");
//
//        try {
//            EasyExcelUtil.writeExcelSheet(response, allData, "供应商报价", sheetNames);
//        } catch(
//                Exception e) {
//            e.printStackTrace();
//        }

    }

    @Override
    public void workOrderDataListExport(WorkOrderDataReq req, HttpServletResponse response) {

        List<WorkOrder> workOrders = workOrderDao.selectList(new LambdaQueryWrapper<>(WorkOrder.class).eq(WorkOrder::getParentId, req.getWorkOrderId()));

        LambdaQueryWrapper<WorkOrderData> wrapper = Wrappers.lambdaQuery(WorkOrderData.class);
        wrapper.in(WorkOrderData::getWorkOrderId, workOrders.stream().map(WorkOrder::getId).collect(Collectors.toList()))
//                .eq(WorkOrderData::getStatus, Constants.WORK_ORDER_DATA_REVIEW_PASS);
                .eq(WorkOrderData::getStatus, req.getStatus());

        List<WorkOrderData> orderData = list(wrapper);
        workOrderDataTemplateExport(orderData, response, "已下单数据-" + DateTimeUtils.getDate("yyyy-MM-dd"), req.getTemplateType(), null);
    }

    @Override
    public void quoteListExport(WorkOrderBatchUpdateReq req, HttpServletResponse response) {

        List<WorkOrder>                   workOrders = workOrderDao.selectList(new LambdaQueryWrapper<>(WorkOrder.class).eq(WorkOrder::getParentId, req.getWorkOrderId()));
        LambdaQueryWrapper<WorkOrderData> wrapper    = new LambdaQueryWrapper<>();
        wrapper.in(WorkOrderData::getWorkOrderId, workOrders.stream().map(WorkOrder::getId).collect(Collectors.toList()));
        List<WorkOrderData> orderData = null;

        wrapper.last("ORDER BY JSON_UNQUOTE(JSON_EXTRACT(data, \"$.account\"))");

        //全部导出
        if(StringUtils.isBlank(req.getWorkerOrderDataIds())) {
            orderData = baseMapper.selectList(wrapper);
        } else {
            //按序号导出
            String[] split = req.getWorkerOrderDataIds().split(",");
            wrapper.clear();
            wrapper.in(WorkOrderData::getId, Arrays.asList(split));
            orderData = baseMapper.selectList(wrapper);
        }
        workOrderDataTemplateExport(orderData, response, "报完价待确认-" + DateTimeUtils.getDate("yyyy-MM-dd"), req.getTemplateType(), null);

    }


    @Override
    public String delete(String workOrderIds) {
        String[]     split = workOrderIds.split(",");
        List<String> ids   = Arrays.asList(split);


        List<Prices> pricesList = pricesBiz.list(new LambdaQueryWrapper<>(Prices.class).in(Prices::getJoinWorkOrderDataId, ids));
        if(pricesList != null && pricesList.size() > 0) {
            throw new CheckException("重新制作或违约数据无法被取消合作");
        }
        removeByIds(ids);
        return null;
    }


    @Override
    @Transactional
    public String lostPromise(String workOrderDataId, String price) {

        if(!price.matches("[1-9]{1}[0-9]{0,}|[1-9]{1}[0-9]{0,}\\.[0-9]{2}") || price.length() > 18) {
            throw new CheckException("金额输入有误，或者超出最大范围");
        }

        //查询违约工单数据
        WorkOrderData workOrderData = getById(workOrderDataId);
        if(workOrderData == null) {
            throw new CheckException("工单数据不存在");
        }

        Map<String, String> map = GsonUtils.gson.fromJson(workOrderData.getData(), new TypeToken<Map<String, String>>() {
        }.getType());

        if("重新制作".equals(map.get("address"))) {
            throw new CheckException("重新制作数据无法被违约");
        }
        map.put("remark", "未执行");
        map.put("price", "");
        map.put("commission", "");
        map.put("img", "");
        map.put("imgTime", "");
        map.put("platPrice", "");
        workOrderData.setData(GsonUtils.gson.toJson(map));
        //修改违约工单 =》报价、佣金清零
        updateById(workOrderData);

        Prices prices = new Prices();
        prices.setActorSn(map.get("actorSn"));
        prices.setCommission(0);
        prices.setPrice(Double.parseDouble(price));
        prices.setProvider(map.get("supplier"));
        prices.setCtime(new Date());
        prices.setUtime(new Date());
        prices.setCreateUserId(-1L);
        prices.setUpdateUserId(-1L);

        //星图/快接单
        map.put("star", "不涉及");
        //信息流授权
        map.put("msgAuth", "不涉及");
        //品牌双微转发授权
        map.put("shareAuth", "不涉及");
        //线下探店
        map.put("face", "不涉及");
        //@
        map.put("at", "不涉及");
        //电商链接
        map.put("linkPrice", "不涉及");
        //报备
        map.put("report", "不涉及");
        //话题
        map.put("topic", "不涉及");
        //电商肖像授权
        map.put("storeAuth", "不涉及");
        //微任务
        map.put("microTask", "不涉及");
        //备注
        map.put("address", "违约费用");
        map.put("price", price + "");
        map.remove("remark");
//        prices.setPriceOnlyDay(map.get("priceOnlyDay"));
        prices.setActorData(GsonUtils.gson.toJson(map));
        prices.setJoinWorkOrderDataId(Long.parseLong(workOrderDataId));
        pricesBiz.save(prices);

        return prices.getId() + "";
    }

//    @Override
//    public List<WorkOrderDataResp> lostPromiseList(String workOrderId) {
//
//        LambdaQueryWrapper<Prices> wrapper = new LambdaQueryWrapper<>();
//        wrapper.eq(Prices::getJoinWorkOrderId, workOrderId);
//        List<Prices> pricesList = pricesBiz.list(wrapper);
//
//        List<WorkOrderDataResp> resps = pricesList.stream().map(x -> {
//            WorkOrderDataResp resp = new WorkOrderDataResp();
//            resp.setData(x.getActorData());
//            resp.setWorkOrderId(x.getJoinWorkOrderId());
//
//            return resp;
//        }).collect(Collectors.toList());
//        return resps;
//    }


    @Override
    @Transactional
    public String remake(String workOrderDataId, String price) {

        if(!price.matches("[1-9]{1}[0-9]{0,}|[1-9]{1}[0-9]{0,}\\.[0-9]{2}") || price.length() > 18) {
            throw new CheckException("金额输入有误，或者超出最大范围");
        }

        //查询重新制作的工单数据
        WorkOrderData workOrderData = getById(workOrderDataId);
        if(workOrderData == null) {
            throw new CheckException("指定工单数据不存在");
        }
        Map<String, String> map = GsonUtils.gson.fromJson(workOrderData.getData(), new TypeToken<Map<String, String>>() {
        }.getType());

        if("违约费用".equals(map.get("address"))) {
            throw new CheckException("违约数据无法被重新制作");
        }

//        map.put("price", "");
//        map.put("commission", "");
        map.put("img", "");
        map.put("imgTime", "");
        map.put("platPrice", "");
        workOrderData.setData(GsonUtils.gson.toJson(map));
        updateById(workOrderData);

        Prices prices = new Prices();


        prices.setActorSn(map.get("actorSn"));
        if(map.get("commission") != null && !"".equals(map.get("commission"))) {
            prices.setCommission(Integer.parseInt(map.get("commission")));
        }

        prices.setPrice(Double.parseDouble(price));
        prices.setProvider(map.get("supplier"));
        prices.setCtime(new Date());
        prices.setUtime(new Date());
        prices.setCreateUserId(-1L);
        prices.setUpdateUserId(-1L);

        //星图/快接单
        map.put("star", "不涉及");
        //信息流授权
        map.put("msgAuth", "不涉及");
        //品牌双微转发授权
        map.put("shareAuth", "不涉及");
        //线下探店
        map.put("face", "不涉及");
        //@
        map.put("at", "不涉及");
        //电商链接
        map.put("linkPrice", "不涉及");
        //报备
        map.put("report", "不涉及");
        //话题
        map.put("topic", "不涉及");
        //电商肖像授权
        map.put("storeAuth", "不涉及");
        //微任务
        map.put("microTask", "不涉及");
        map.put("address", "重新制作");
        map.put("price", price + "");
        String data = GsonUtils.gson.toJson(map);
        prices.setActorData(data);
        workOrderData.setData(data);
        prices.setJoinWorkOrderDataId(Long.parseLong(workOrderDataId));
        //重新制作
        pricesBiz.save(prices);
        return workOrderData.getId() + "";
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

    @Override
    public void workOrderDataTemplateExport(List<WorkOrderData> orderData, HttpServletResponse response, String
            excelName, String templateType, String isSupplier) {

        List<WorkOrderDataBo> excelList = new ArrayList<>();

        for(int i = 0; i < orderData.size(); i++) {
            WorkOrderData   workOrderData   = orderData.get(i);
            WorkOrderDataBo workOrderDataBo = GsonUtils.gson.fromJson(workOrderData.getData(), WorkOrderDataBo.class);
            //Double => int
            Double price = Double.parseDouble(StringUtils.isBlank(workOrderDataBo.getPrice()) ? "0.0" : workOrderDataBo.getPrice());
            workOrderDataBo.setPrice(price.intValue() + "");
            excelList.add(workOrderDataBo);
        }

        try {
            EasyExcelUtil.writeExcelSheet(response, excelList, excelName, templateType, isSupplier);
        } catch(Exception e) {
            e.printStackTrace();
        }

    }

    @Override
    public String enquiryImport(MultipartFile file, WorkOrderReq req, HttpServletResponse response) {
        //校验文件类型
        if(!file.getOriginalFilename().endsWith("xls") && !file.getOriginalFilename().endsWith("xlsx")) {
            log.error(file.getOriginalFilename() + "不是excel文件");
            throw new CheckException(file.getOriginalFilename() + "不是excel文件");
        }
        List<Object> data = null;
        try {
            //读取了excel的全部数据
            data = EasyExcelUtil.readExcelOnlySheet1(file.getInputStream());
        } catch(Exception e) {
            log.error(">>> Excel读取失败:{}", e);
            throw new CheckException("Excel读取失败,请联系管理员");
        }
        List<String> selfTitle = workOrderBiz.excelTitle(req.getExcelType());
        //获取模板表头（非读取）
        selfTitle.addAll(excelTitle(req.getExcelType()));

        if(null != data) {
            // 判断表头             数字9是读取模板标题名称（可能会改动）
            if(data.size() < 10)
                throw new CheckException("Excel不匹配,请勿修改或重新下载模版");
            LinkedHashMap<Integer, String> title = (LinkedHashMap<Integer, String>) data.get(9);
            List<String>                   list  = title.values().stream().collect(Collectors.toList());

            if(list.size() != selfTitle.size()) {
                throw new CheckException("Excel文件标题不匹配,请勿修改或重新下载模版");
            }

            if(!selfTitle.toString().equalsIgnoreCase(list.toString())) {
                throw new CheckException("Excel文件标题不匹配,请勿修改或重新下载模版");
            }

            LambdaQueryWrapper<WorkOrderData> wrapper = new LambdaQueryWrapper<>();

            //重新询价
            WorkOrderBatchUpdateReq     reqAgain                  = new WorkOrderBatchUpdateReq();
            Set<WorkOrderDataUpdateReq> workOrderDataUpdateReqSet = new HashSet<>();

            //找到父工单id
            List<WorkOrder> workOrderList = workOrderDao.selectList(new LambdaQueryWrapper<>(WorkOrder.class).eq(WorkOrder::getParentId, req.getWorkOrderId()));

            List<Long> idList = workOrderList.stream().map(WorkOrder::getId).collect(Collectors.toList());

            wrapper.in(WorkOrderData::getWorkOrderId, idList);

            List<WorkOrderData> workOrderData = baseMapper.selectList(wrapper);
            if(workOrderData == null || workOrderData.size() == 0) {
                return null;
            }
            //sb用于链接下面唯一标识使用，保证顺序性
            StringBuilder sb = new StringBuilder();


            Map<String, List<WorkOrderData>> workOrderDataMap = workOrderData.stream().collect(Collectors.groupingBy(x -> {
                Map<String, String> map = GsonUtils.gson.fromJson(x.getData(), new TypeToken<Map<String, String>>() {
                }.getType());
                //平台+账户id+资源报价
                String media     = map.get("media");
                String IDorLink  = map.get("IDorLink");
                String address   = map.get("address");
                String at        = map.get("at") == null ? "" : map.get("at");
                String topic     = map.get("topic") == null ? "" : map.get("topic");
                String linkPrice = map.get("linkPrice") == null ? "" : map.get("linkPrice");
                String shareAuth = map.get("shareAuth") == null ? "" : map.get("shareAuth");
                String storeAuth = map.get("storeAuth") == null ? "" : map.get("storeAuth");
                String msgAuth   = map.get("msgAuth") == null ? "" : map.get("msgAuth");
                String face      = map.get("face") == null ? "" : map.get("face");

                if("1".equals(req.getExcelType())) {
                    sb.delete(0, sb.length());
                    //@+话题+电商链接+双微转发+电商肖像授权+信息流授权+星图/快接单+线下探店      ====>抖音快手权益
                    String star = map.get("star") == null ? "" : map.get("star");
                    sb.append(media).append(IDorLink).append(address).append(at)
                            .append(topic).append(linkPrice).append(shareAuth).append(storeAuth).append(msgAuth).append(star).append(face);
                    return MD5Util.getMD5(sb.toString());
                } else {
                    sb.delete(0, sb.length());
                    //@+话题+电商链接+双微转发+电商肖像授权+信息流授权+微任务（微博）+报备（小红书）+线下探店      ====>非 抖音快手权益
                    String microTask = map.get("microTask") == null ? "" : map.get("microTask");
                    String report    = map.get("report") == null ? "" : map.get("report");
                    sb.append(media).append(IDorLink).append(address).append(at)
                            .append(topic).append(linkPrice).append(shareAuth).append(storeAuth).append(msgAuth).append(microTask).append(report).append(face);
                    return MD5Util.getMD5(sb.toString());
                }
            }, Collectors.mapping(x -> x, Collectors.toList())));


            for(int x = 10; x < data.size(); x++) {
                LinkedHashMap<Integer, String> bo = (LinkedHashMap<Integer, String>) data.get(x);
                //bo.get(0) 的序号0 都是以excel的下标获取的值，以下统一
                String media     = bo.get(0) == null ? "" : bo.get(0);
                String IDorLink  = bo.get(3) == null ? "" : bo.get(3);
                String address   = bo.get(5) == null ? "" : bo.get(5);
                String at        = bo.get(9) == null ? "" : bo.get(9);
                String topic     = bo.get(10) == null ? "" : bo.get(10);
                String linkPrice = bo.get(11) == null ? "" : bo.get(11);
                String shareAuth = bo.get(12) == null ? "" : bo.get(12);
                String storeAuth = bo.get(13) == null ? "" : bo.get(13);
                String msgAuth   = bo.get(14) == null ? "" : bo.get(14);

                if("1".equals(req.getExcelType())) {
                    sb.delete(0, sb.length());
                    String star = bo.get(15) == null ? "" : bo.get(15);
                    String face = bo.get(16) == null ? "" : bo.get(16);

                    sb.append(media).append(IDorLink).append(address).append(at)
                            .append(topic).append(linkPrice).append(shareAuth).append(storeAuth).append(msgAuth).append(star).append(face);
                } else {
                    sb.delete(0, sb.length());
                    //@+话题+电商链接+双微转发+电商肖像授权+信息流授权+微任务（微博）+报备（小红书）+线下探店      ====>非 抖音快手权益
                    String microTask = bo.get(15) == null ? "" : bo.get(15);
                    String report    = bo.get(16) == null ? "" : bo.get(16);
                    String face      = bo.get(17) == null ? "" : bo.get(17);
                    sb.append(media).append(IDorLink).append(address).append(at)
                            .append(topic).append(linkPrice).append(shareAuth).append(storeAuth).append(msgAuth).append(microTask).append(report).append(face);
                }

                //获取Excel中的 平台+账户id+资源报价+权益 （唯一标识）
                String              no             = MD5Util.getMD5(sb.toString());
                List<WorkOrderData> workOrderDatum = workOrderDataMap.get(no);
                if(workOrderDatum == null) {
                    continue;
                }
                WorkOrderDataUpdateReq updateReq = null;

                reqAgain.setWorkOrderId(Long.parseLong(req.getWorkOrderId()));
                for(WorkOrderData orderData : workOrderDatum) {
                    updateReq = new WorkOrderDataUpdateReq();
                    updateReq.setData(orderData.getData());
                    updateReq.setId(orderData.getId());
                    workOrderDataUpdateReqSet.add(updateReq);
                }

            }
            reqAgain.setWorkOrderId(Long.parseLong(req.getWorkOrderId()));
            reqAgain.setList(workOrderDataUpdateReqSet.stream().collect(Collectors.toList()));
            again(reqAgain);
        }
        return null;
    }

    @Override
    public List<SupplierImportResp> supplierImport(MultipartFile file, WorkOrderReq req, HttpServletResponse response) {
        //校验文件类型
        if(!file.getOriginalFilename().endsWith("xls") && !file.getOriginalFilename().endsWith("xlsx")) {
            log.error(file.getOriginalFilename() + "不是excel文件");
            throw new CheckException(file.getOriginalFilename() + "不是excel文件");
        }
        List<Object> data = null;
        try {
            data = EasyExcelUtil.readExcelOnlySheet1(file.getInputStream());
        } catch(Exception e) {
            log.error(">>> Excel读取失败:{}", e);
            throw new CheckException("Excel读取失败,请联系管理员");
        }

        List<String>        selfTitle         = excelTitleBySupplier(req.getExcelType());
        List<WorkOrderData> workOrderDataList = new ArrayList<>();

        if(null != data) {
            // 判断表头             数字9是读取模板标题名称（可能会改动）
            if(data.size() < 10)
                throw new CheckException("Excel不匹配,请勿修改或重新下载模版");
            LinkedHashMap<Integer, String> title = (LinkedHashMap<Integer, String>) data.get(9);
            List<String>                   list  = title.values().stream().collect(Collectors.toList());

            if(list.size() < selfTitle.size()) {
                throw new CheckException("Excel文件标题不匹配,请勿修改或重新下载模版");
            }
            //截取需要填写的字段
            List<String> titleList = list.subList(list.size() - selfTitle.size(), list.size());
            if(!selfTitle.toString().equalsIgnoreCase(titleList.toString())) {
                throw new CheckException("Excel文件标题不匹配,请勿修改或重新下载模版");
            }

            //重新询价的工单数据集合
            List<WorkOrderData> reList = list(new LambdaQueryWrapper<>(WorkOrderData.class).eq(WorkOrderData::getWorkOrderId, req.getWorkOrderId()));

            StringBuilder sb = new StringBuilder();

            //将工单数据转成map<唯一标识，工单数据集合>形式，用于查询
            Map<String, List<WorkOrderData>> workOrderDataMap = reList.stream().collect(Collectors.groupingBy(x -> {
                Map<String, String> map = GsonUtils.gson.fromJson(x.getData(), new TypeToken<Map<String, String>>() {
                }.getType());
                //平台+账户id+资源报价
                String media     = map.get("media");
                String IDorLink  = map.get("IDorLink");
                String address   = map.get("address");
                String at        = map.get("at") == null ? "" : map.get("at");
                String topic     = map.get("topic") == null ? "" : map.get("topic");
                String linkPrice = map.get("linkPrice") == null ? "" : map.get("linkPrice");
                String shareAuth = map.get("shareAuth") == null ? "" : map.get("shareAuth");
                String storeAuth = map.get("storeAuth") == null ? "" : map.get("storeAuth");
                String msgAuth   = map.get("msgAuth") == null ? "" : map.get("msgAuth");
                String face      = map.get("face") == null ? "" : map.get("face");

                if("1".equals(req.getExcelType())) {
                    sb.delete(0, sb.length());
                    //@+话题+电商链接+双微转发+电商肖像授权+信息流授权+星图/快接单+线下探店      ====>抖音快手权益
                    String star = map.get("star") == null ? "" : map.get("star");
                    sb.append(media).append(IDorLink).append(address).append(at)
                            .append(topic).append(linkPrice).append(shareAuth).append(storeAuth).append(msgAuth).append(star).append(face);
                    return MD5Util.getMD5(sb.toString());
                } else {
                    sb.delete(0, sb.length());
                    //@+话题+电商链接+双微转发+电商肖像授权+信息流授权+微任务（微博）+报备（小红书）+线下探店      ====>非 抖音快手权益
                    String microTask = map.get("microTask") == null ? "" : map.get("microTask");
                    String report    = map.get("report") == null ? "" : map.get("report");
                    sb.append(media).append(IDorLink).append(address).append(at)
                            .append(topic).append(linkPrice).append(shareAuth).append(storeAuth).append(msgAuth).append(microTask).append(report).append(face);
                    return MD5Util.getMD5(sb.toString());
                }
            }, Collectors.mapping(x -> x, Collectors.toList())));

            for(int x = 10; x < data.size(); x++) {
                LinkedHashMap<Integer, String> bo = (LinkedHashMap<Integer, String>) data.get(x);

                String media     = bo.get(0) == null ? "" : bo.get(0);
                String IDorLink  = bo.get(3) == null ? "" : bo.get(3);
                String address   = bo.get(5) == null ? "" : bo.get(5);
                String at        = bo.get(9) == null ? "" : bo.get(9);
                String topic     = bo.get(10) == null ? "" : bo.get(10);
                String linkPrice = bo.get(11) == null ? "" : bo.get(11);
                String shareAuth = bo.get(12) == null ? "" : bo.get(12);
                String storeAuth = bo.get(13) == null ? "" : bo.get(13);
                String msgAuth   = bo.get(14) == null ? "" : bo.get(14);

                if("1".equals(req.getExcelType())) {
                    sb.delete(0, sb.length());
                    String star = bo.get(15) == null ? "" : bo.get(15);
                    String face = bo.get(16) == null ? "" : bo.get(16);

                    sb.append(media).append(IDorLink).append(address).append(at)
                            .append(topic).append(linkPrice).append(shareAuth).append(storeAuth).append(msgAuth).append(star).append(face);
                } else {
                    sb.delete(0, sb.length());
                    //@+话题+电商链接+双微转发+电商肖像授权+信息流授权+微任务（微博）+报备（小红书）+线下探店      ====>非 抖音快手权益
                    String microTask = bo.get(15) == null ? "" : bo.get(15);
                    String report    = bo.get(16) == null ? "" : bo.get(16);
                    String face      = bo.get(17) == null ? "" : bo.get(17);
                    sb.append(media).append(IDorLink).append(address).append(at)
                            .append(topic).append(linkPrice).append(shareAuth).append(storeAuth).append(msgAuth).append(microTask).append(report).append(face);
                }

                //获取Excel中的 平台+账户id+资源报价+权益 （唯一标识）
                String no = MD5Util.getMD5(sb.toString());


                if(workOrderDataMap.containsKey(no)) {
                    List<WorkOrderData> workOrderDatas = workOrderDataMap.get(no);
                    if(workOrderDatas == null) {
                        continue;
                    }

                    for(WorkOrderData workOrderData : workOrderDatas) {
                        Map<String, String> map = GsonUtils.gson.fromJson(workOrderData.getData(), new TypeToken<Map<String, String>>() {
                        }.getType());

                        if("1".equals(req.getExcelType())) {
                            if(bo.get(25) == null || bo.get(25).startsWith("不涉及")) {
                                bo.put(25, "0");
                            }
                            map.put("img", bo.get(20));
                            map.put("imgTime", bo.get(21));
                            map.put("platPrice", bo.get(22));
                            map.put("sale", bo.get(23) == null ? "0" : bo.get(23).endsWith("%") ? bo.get(23).substring(0, bo.get(23).indexOf("%")) : bo.get(23));
                            map.put("price", bo.get(24));
                            map.put("commission", bo.get(25).endsWith("%") ? bo.get(25).substring(0, bo.get(25).indexOf("%")) : bo.get(25));
                            map.put("fansCount", bo.get(26));
                            map.put("scheduleStartTime", bo.get(27));
                            map.put("scheduleEndTime", bo.get(28));
                            map.put("priceOnlyDay", bo.get(29));
                            map.put("remark", bo.get(30));
                        } else {
                            if(bo.get(22) == null || bo.get(22).startsWith("不涉及")) {
                                bo.put(22, "0");
                            }
                            map.put("price", bo.get(21));
                            map.put("commission", bo.get(22).endsWith("%") ? bo.get(22).substring(0, bo.get(22).indexOf("%")) : bo.get(22));
                            map.put("fansCount", bo.get(23));
                            map.put("scheduleStartTime", bo.get(24));
                            map.put("scheduleEndTime", bo.get(25));
                            map.put("priceOnlyDay", bo.get(26));
                            map.put("remark", bo.get(27));

                        }
                        workOrderData.setData(GsonUtils.gson.toJson(map));
                        workOrderData.setId(workOrderData.getId());
                        workOrderData.setUtime(new Date());
                        workOrderData.setUpdateUserId(UserInfoContext.getUserId());
                        workOrderDataList.add(workOrderData);
                    }

                }

            }
            //供应商报价修改
//            updateBatchById(workOrderDataList);
        }
        return workOrderDataList.stream().map(x -> {
            SupplierImportResp resp = new SupplierImportResp();
            BeanUtils.copyProperties(x, resp);
            return resp;
        }).collect(Collectors.toList());
    }

    public void WorkOrderDataExport(List<WorkOrderData> orderData, HttpServletResponse response, String
            excelName) {
        Fields fields = fieldsBiz.getById(Constants.FIELD_TYPE_QUOTE);
        //获取字段列表
        List<FieldsBo> fieldsBos = GsonUtils.gson.fromJson(fields.getFieldList(), new TypeToken<ArrayList<FieldsBo>>() {
        }.getType());

        //全部数据
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

        if(outboundData.size() == 1 && xinyiData.size() == 1 && weigeData.size() == 1) {
            EasyExcelUtil.writeExcel(response, null, excelName);
            return;
        }

        //Excel 的sheetName
        List<String> sheetNames = Arrays.asList("库外数据", Constants.SUPPLIER_XIN_YI, Constants.SUPPLIER_WEI_GE);
        try {
            EasyExcelUtil.writeExcelSheet(response, allData, excelName, sheetNames);
        } catch(
                Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * @param type 1是抖音快手模板，其他是非抖音快手模板
     * @return 供应商填写模板表头
     */
    private List<String> excelTitleBySupplier(String type) {
        String arr[] = null;
        if("1".equals(type)) {
            arr = new String[]{"星图/快接单平台截图", "截图时间", "星图/快接单平台报价（元）", "折扣（%）", "执行报价（元）", "佣金", "粉丝数", "可发布开始时间", "发布结束时间", "仅保价一天", "备注"};
        } else {
            arr = new String[]{"总价(元)", "佣金", "粉丝数", "可发布开始时间", "发布结束时间", "仅保价一天", "备注"};
        }
        return new ArrayList<>(Arrays.asList(arr));
    }

    /**
     * @param type 1是抖音快手模板，其他是非抖音快手模板
     * @return 供应商填写模板表头
     */
    private List<String> excelTitle(String type) {
        String arr[] = null;
        if("1".equals(type)) {
            arr = new String[]{"星图/快接单平台截图", "截图时间", "星图/快接单平台报价（元）", "折扣（%）", "执行报价（元）", "佣金", "备注"};
        } else {
            arr = new String[]{"总价(元)", "佣金", "备注"};
        }
        return new ArrayList<>(Arrays.asList(arr));
    }
}
