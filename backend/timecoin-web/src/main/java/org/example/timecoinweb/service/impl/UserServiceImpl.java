package org.example.timecoinweb.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.extern.slf4j.Slf4j;
import org.example.pojo.*;
import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.mapper.ActivityMapper;
import org.example.timecoinweb.mapper.AdmiMapper;
import org.example.timecoinweb.mapper.OldMapper;
import org.example.timecoinweb.mapper.UserMapper;
import org.example.timecoinweb.mapper.VolMapper;
import org.example.timecoinweb.service.TimeCoinChainService;
import org.example.timecoinweb.service.UserService;
import org.example.timecoinweb.util.ServiceTypeSupport;
import org.example.utils.DistanceUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class UserServiceImpl implements UserService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    ActivityMapper activityMapper;
    @Autowired
    UserMapper userMapper;
    @Autowired
    OldMapper oldMapper;
    @Autowired
    AdmiMapper admiMapper;
    @Autowired
    VolMapper volMapper;
    @Autowired
    DistanceUtils distanceUtils;
    @Autowired
    BlockchainProperties blockchainProperties;
    @Autowired
    TimeCoinChainService timeCoinChainService;
    @Autowired
    org.example.timecoinweb.service.CoinBalanceLedgerService coinBalanceLedgerService;
    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired
    org.example.timecoinweb.service.SettlementService settlements;
    @Autowired
    @Lazy
    private UserService userService;

    /**
     * 老人增加活动
     * @param activity
     * @param userId
     */
    @Override
    public Map<String, Object> oldAddActivity(Activity activity, Integer userId, String requestId) {
        //用用户io查老人id
        Integer oldId=oldMapper.selectOldId(userId);
        if (oldId == null) throw new IllegalStateException("老人资料不存在");

        //补充数据
        activity.setRemain(activity.getQuota());
        activity.setCreateTime(LocalDateTime.now());
        activity.setUpdateTime(LocalDateTime.now());
        activity.setOldId(oldId);
        Integer administratorTableId = admiMapper.selectFirstAdministratorId();
        if (administratorTableId == null) {
            throw new IllegalStateException(
                    "系统中还没有管理员账号，请联系平台管理员完成初始化。");
        }
        activity.setAdministratorId(administratorTableId);

        normalizeVolunteerRewardForPublish(activity);
        ServiceTypeSupport.normalizeForCreate(activity, OBJECT_MAPPER);

        return settlements.submitPublish(activity, userId, requestId);
    }

    /** 移动端发布活动：校验每人答谢时间币上限；null 当作 0。 */
    private void normalizeVolunteerRewardForPublish(Activity activity) {
        Integer r = activity.getVolunteerReward();
        if (r == null) {
            activity.setVolunteerReward(0);
            return;
        }
        if (r < 0) {
            throw new IllegalArgumentException("答谢时间币不能为负数");
        }
        BigInteger max = blockchainProperties.getVolunteerRewardMax();
        if (max != null && max.signum() > 0 && BigInteger.valueOf(r).compareTo(max) > 0) {
            throw new IllegalArgumentException("答谢时间币不能超过单次上限 " + max);
        }
    }

    /**
     * 老人更新或修改活动数据
     * @param activity
     */
    @Transactional
    @Override
    public void update(Activity activity, Integer userId) {
        Activity saved = requireOwnedActivity(activityMapper.lockById(activity.getId()), userId);
        requireEditableActivity(saved);
        // 审核状态、归属、名额与管理员字段不能由客户端指定。
        activity.setOldId(saved.getOldId());
        activity.setAdministratorId(saved.getAdministratorId());
        activity.setCreateTime(saved.getCreateTime());
        activity.setStatus((short) 1);
        activity.setMessage(null);
        if (activity.getQuota() != null && activity.getQuota() <= 0) {
            throw new IllegalArgumentException("活动名额必须大于0");
        }
        activity.setRemain(activity.getQuota() == null ? saved.getRemain() : activity.getQuota());
        normalizeVolunteerRewardForPublish(activity);
        activity.setUpdateTime(LocalDateTime.now());
        ServiceTypeSupport.normalizeForUpdate(activity, OBJECT_MAPPER);

        activityMapper.update(activity);
    }

    /**
     * 老人删除活动
     * @param ids
     */
    @Transactional
    @Override
    public void oldDeleteActivity(List<Integer> ids, Integer userId) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("请选择活动");
        List<Integer> orderedIds = new ArrayList<>(new java.util.TreeSet<>(ids));
        for (Integer id : orderedIds) {
            Activity activity = requireOwnedActivity(activityMapper.lockById(id), userId);
            requireEditableActivity(activity);
        }
        volMapper.deleteVolActByActIds(ids);
        activityMapper.deleteById(ids);
    }

    private Activity requireOwnedActivity(Activity activity, Integer userId) {
        Integer oldId = userId == null ? null : oldMapper.selectOldId(userId);
        if (activity == null || oldId == null || activity.getOldId() != oldId) {
            throw new IllegalStateException("无权操作该活动或活动不存在");
        }
        return activity;
    }

    private void requireEditableActivity(Activity activity) {
        settlements.requireActivityMutable(activity);
        if (activity.getStatus() == null || (activity.getStatus() != 1 && activity.getStatus() != 4)
                || !volMapper.selectUsers(activity.getId()).isEmpty()) {
            throw new IllegalStateException("仅可编辑或删除无人报名的待审核、已拒绝活动");
        }
    }

    /**
     * 老人搜寻自己的活动
     * @param page
     * @param pageSize
     * @return
     */
    @Override
    public PageBean oldSelectActivity(Integer page, Integer pageSize,Integer userId) {
        //找到老人的老人id
        Integer oldId=oldMapper.selectOldId(userId);

        //1.设置分页参数
        PageHelper.startPage(page, pageSize);

        //2.执行查询
        List<Activity> activityList = activityMapper.selectPageActivity(oldId,null,null,null,null,null,null,null);
        Page<Activity> p=(Page<Activity>) activityList;

        //3.封装PageBean对象
        PageBean pageBean=new PageBean(p.getTotal(),p.getResult());

        return pageBean;
    }

    /**
     * 用户（志愿者）接受活动，找到志愿者id并维护中间表,让remain-1
     * @param activityId
     * @param userId
     */
    @Transactional
    @Override
    public String volAcceptActivity(Integer activityId, Integer userId) {
        //1.找到该志愿者的志愿者id
        Integer volId=volMapper.selectVolId(userId);
        if (volId == null) {
            return "志愿者信息不存在，请重新登录";
        }

        // 报名与取消统一先锁活动，再修改报名记录。
        requireRegistrationOpen(activityMapper.lockById(activityId));

        //2.已报名直接返回，避免重复扣减
        VolActivity existed = volMapper.volSelectByActVolId(activityId, volId);
        if (existed != null) {
            return "您已报名该活动";
        }

        //3.仅在剩余名额>0时扣减
        int affected = activityMapper.lessRemain(activityId);
        if (affected <= 0) {
            return "剩余名额不够";
        }

        //4.维护志愿者、活动中间表
        try {
            volMapper.insertVolAct(volId, activityId);
        } catch (DuplicateKeyException e) {
            // 避免扣减成功但重复报名失败导致 remain 不一致
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return "您已报名该活动";
        } catch (DataAccessException e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            throw e;
        }

        return "1";
    }


    @Override
    public PageBean volSelectMine(Integer id,Integer page, Integer pageSize, String title, LocalDate date, LocalTime begin, LocalTime end, String address,Short status) {
        //找到用户的志愿者id
        Integer volId=volMapper.selectVolId(id);

        //用志愿者ID搜寻出活动Id
        List<Integer> actIds=volMapper.selectActIds(volId);

        //1.设置分页参数
        PageHelper.startPage(page, pageSize);

        //2.执行查询
        List<Activity> activityList = volMapper.volSelectPageActivity(volId,null,title, date, begin, end, status, address,actIds);
        Page<Activity> p=(Page<Activity>) activityList;

        //3.封装PageBean对象
        PageBean pageBean=new PageBean(p.getTotal(),p.getResult());

        return pageBean;
    }

    @Transactional
    @Override
    public void volCancel(Integer userId, Integer activityId) throws DataAccessException{
        Integer volId=volMapper.selectVolId(userId);
        if (volId == null) {
            throw new IllegalArgumentException("志愿者信息不存在，请重新登录");
        }
        Activity activity = activityMapper.lockById(activityId);
        if (activity == null) {
            throw new IllegalArgumentException("活动不存在");
        }
        if (settlements.hasReward(activityId, volId)) throw new IllegalStateException("服务已进入结算，不能取消报名");
        // 已取消的重试请求不再次返还名额。
        if (volMapper.volSelectByActVolId(activityId, volId) == null) {
            return;
        }
        requireRegistrationOpen(activity);
        if (volMapper.volCancel(volId, activityId) == 1
                && activityMapper.moreRemain(activityId) != 1) {
            throw new IllegalStateException("活动名额不一致，取消操作已回滚");
        }
    }

    private void requireRegistrationOpen(Activity activity) {
        if (activity == null) {
            throw new IllegalArgumentException("活动不存在");
        }
        Short status = activity.getStatus();
        if (status == null || (status != 2 && status != 3)) {
            throw new IllegalStateException("活动未开放报名或已结束");
        }
        if (activity.getDeadline() == null || !activity.getDeadline().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("活动报名已截止");
        }
        if (activity.getDate() == null || activity.getEnd() == null
                || !LocalDateTime.of(activity.getDate(), activity.getEnd()).isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("活动已经结束");
        }
    }

    @Override
    public PageBean volSelectActivity(Integer id, Integer page, Integer pageSize, String title, LocalDate date, LocalTime begin, LocalTime end, String address,Short status,LocalDateTime deadline) {
        //志愿者的志愿者ID
        Integer volId=volMapper.selectVolId(id);
        //找到已经报名的活动id
        List<Integer> actIds=volMapper.selectActIds(volId);

        //1.设置分页参数
        PageHelper.startPage(page, pageSize);

        //2.执行查询
        List<Activity> activities=volMapper.volSelectNotActivity(actIds,title,date,begin,end,status,address,deadline);//未报名活动
        Page<Activity> p=(Page<Activity>) activities;

        //3.封装PageBean对象
        PageBean pageBean=new PageBean(p.getTotal(),p.getResult());

        return pageBean;
    }

    @Override
    public PageBean oldSelectVolStatus(Integer id, Integer page, Integer pageSize, Integer userId) {
        requireOwnedActivity(activityMapper.selectByActId(id), userId);
        //1.设置分页参数
        PageHelper.startPage(page, pageSize);

        //2.执行查询
        List<Vol> users=volMapper.selectUsers(id);
        Page<Vol> p=(Page<Vol>) users;

        //3.封装PageBean对象
        PageBean pageBean=new PageBean(p.getTotal(),p.getResult());

        return pageBean;
    }

    /** 校验与结算单/outbox在短事务中提交；消费者确认原交易后完成台账和状态。 */
    @Override
    public Map<String, Object> updateVolActivity(VolActivity volActivity, Integer userId) {
        if ((volActivity.getStatus() != null && volActivity.getStatus() != 0 && volActivity.getStatus() != 1)
                || (volActivity.getSign() != null && volActivity.getSign() != 0 && volActivity.getSign() != 1)) {
            throw new IllegalArgumentException("签到和完成状态只能为0或1");
        }
        Integer volId = volMapper.selectVolId(volActivity.getUserId());
        volActivity.setVolId(volId);
        org.springframework.transaction.support.TransactionTemplate transaction =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        return transaction.execute(tx -> {
            Activity activity = requireOwnedActivity(activityMapper.lockById(volActivity.getActivityId()), userId);
            VolActivity locked = requireVolunteerRow(volActivity.getActivityId(), volId);
            volActivity.setRewardPaid(locked.getRewardPaid());
            Short newStatus = volActivity.getStatus();
            boolean paid = locked.getRewardPaid() != null && locked.getRewardPaid() == 1;
            if (newStatus != null && newStatus == 0 && (paid || settlements.hasReward(activity.getId(), volId))) {
                throw new IllegalStateException("已答谢或正在结算的服务不可撤回完成，请联系管理员处理争议");
            }
            if (newStatus != null && newStatus == 1) {
                if (activity.getDate() == null || activity.getEnd() == null
                        || LocalDateTime.now().isBefore(LocalDateTime.of(activity.getDate(), activity.getEnd()))) {
                    throw new IllegalStateException("活动尚未结束，无法确认完成");
                }
                if (!paid && activity.getVolunteerReward() != null && activity.getVolunteerReward() > 0) {
                    return settlements.submitReward(activity, volActivity, userId);
                }
            }
            volActivity.setUpdateTime(LocalDateTime.now());
            volMapper.update(volActivity);
            return Map.of("state", "SUCCEEDED");
        });
    }

    private VolActivity requireVolunteerRow(Integer activityId, Integer volId) {
        VolActivity locked = volMapper.lockVolunteerActivityRow(activityId, volId);
        if (locked == null) throw new IllegalStateException("未找到该志愿者的报名信息");
        return locked;
    }

    @Override
    public Map<String, Object> batchCompleteVolunteers(Integer elderlyUserId, Integer activityId) {
        Integer oldId = oldMapper.selectOldId(elderlyUserId);
        if (oldId == null) {
            throw new IllegalStateException("当前账号不是老人用户");
        }
        Activity activity = activityMapper.selectByActId(activityId);
        if (activity == null) {
            throw new IllegalStateException("活动不存在");
        }
        if (activity.getOldId() != oldId) {
            throw new IllegalStateException("无权操作该活动");
        }
        if (activity.getDate() == null || activity.getEnd() == null) {
            throw new IllegalStateException("活动时间不完整，无法批量答谢");
        }
        LocalDateTime activityEnd = LocalDateTime.of(activity.getDate(), activity.getEnd());
        if (LocalDateTime.now().isBefore(activityEnd)) {
            throw new IllegalStateException("活动尚未结束，请结束后再一键答谢");
        }

        List<Vol> volunteers = volMapper.selectUsers(activityId);
        int successCount = 0;
        int acceptedCount = 0;
        int skipCount = 0;
        List<Map<String, String>> failures = new ArrayList<>();

        for (Vol vol : volunteers) {
            if (vol.getStatus() != null && vol.getStatus() == 1) {
                skipCount++;
                continue;
            }
            VolActivity volActivity = new VolActivity();
            volActivity.setUserId(vol.getId());
            volActivity.setActivityId(activityId);
            volActivity.setStatus((short) 1);
            try {
                Map<String, Object> receipt = userService.updateVolActivity(volActivity, elderlyUserId);
                if ("SUCCEEDED".equals(receipt.get("state"))) successCount++;
                else acceptedCount++;
            } catch (Exception e) {
                Map<String, String> fail = new HashMap<>();
                fail.put("userId", String.valueOf(vol.getId()));
                fail.put("name", vol.getName() != null ? vol.getName() : "");
                fail.put("reason", e.getMessage() != null ? e.getMessage() : "操作失败");
                failures.add(fail);
                log.warn("批量答谢失败 userId={} activityId={}", vol.getId(), activityId, e);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("successCount", successCount);
        result.put("acceptedCount", acceptedCount);
        result.put("skipCount", skipCount);
        result.put("failCount", failures.size());
        result.put("failures", failures);
        result.put("rewardPerVolunteer",
                activity.getVolunteerReward() != null ? activity.getVolunteerReward() : 0);
        return result;
    }

    @Override
    public void updateImage(String local, Integer id) {
        userMapper.updateImage(local, id);
    }

    /** 兼容 MyBatis Map 键名大小写不一致（如 ROLE / role） */
    private static Object mapGetIgnoreCase(Map<String, Object> row, String... keyCandidates) {
        for (String want : keyCandidates) {
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(want)) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    @Override
    public java.util.Map<String, Object> getUserStats() {
        List<java.util.Map<String, Object>> roleStats = userMapper.countUsersByRole();
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        for (java.util.Map<String, Object> roleStat : roleStats) {
            Object role = mapGetIgnoreCase(roleStat, "user_role", "role");
            Object count = mapGetIgnoreCase(roleStat, "role_cnt", "count");
            if (role != null) {
                String roleKey = "";
                switch (role.toString()) {
                    case "1": roleKey = "elder"; break;
                    case "2": roleKey = "volunteer"; break;
                    case "3": roleKey = "admin"; break;
                }
                if (!roleKey.isEmpty()) {
                    long n = 0;
                    if (count instanceof Number) {
                        n = ((Number) count).longValue();
                    } else if (count != null) {
                        n = Long.parseLong(count.toString());
                    }
                    stats.put(roleKey, n);
                }
            }
        }
        return stats;
    }

    @Transactional
    @Override
    public Boolean volSignIn(Activity activity, Integer userId) {
        //1.得到志愿者id
        Integer volId=volMapper.selectVolId(userId);
        //2.找到该活动的地址
        String actAddress=activityMapper.selectAddress(activity);

        //3.保存志愿者用户的签到列表
        String userAddress=activity.getAddress();

        //4.检查用户地址与活动地址是否匹配
        double maxDistanceInMeters=100;//设置最大距离为100
        boolean judge= distanceUtils.isWithinDistance(userAddress,actAddress,maxDistanceInMeters);

        if(judge){
            volMapper.volSignIn(volId,activity.getId());

            return true;
        }

        return false;
    }

    @Override
    public VolActivity volCheckMine(Integer activityId, Integer userId) {
        Integer volId=volMapper.selectVolId(userId);

        //用志愿者id和活动id找信息
        VolActivity volActivity=volMapper.volSelectByActVolId(activityId,volId);
        if(volActivity!=null) {
            volActivity.setUserId(userId);
        }

        return volActivity;
    }
}
