package org.example.timecoinweb.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pojo.Activity;
import org.example.pojo.User;
import org.example.pojo.Vol;
import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.mapper.ActivityMapper;
import org.example.timecoinweb.mapper.OldMapper;
import org.example.timecoinweb.mapper.UserMapper;
import org.example.timecoinweb.mapper.VolMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * N4：批量答谢异步化。
 * - submit() 只做 J3 预检（人数/总额/余额）并创建作业，立即返回 jobId，避免前端 180s 超时。
 * - 单线程后台逐笔调用 UserService.updateVolActivity（复用 J2 签到校验 + J3 单笔上限 + 幂等）。
 * - 进度落 batch_reward_job，前端按 jobId 轮询；作业归属 owner，越权查询被拒。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchRewardService {
    private final JdbcTemplate jdbc;
    private final UserMapper userMapper;
    private final VolMapper volMapper;
    private final ActivityMapper activityMapper;
    private final OldMapper oldMapper;
    private final UserService userService;
    private final BlockchainProperties props;

    /** 单线程顺序执行，避免并发答谢同一老人余额竞争（与链上 nonce 串行一致）。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "batch-reward-worker");
        t.setDaemon(true);
        return t;
    });

    /** 创建异步作业；预检失败抛异常（HTTP 400/409），成功返回 jobId。 */
    public String submit(Integer ownerId, Integer activityId) {
        Activity activity = activityMapper.selectByActId(activityId);
        if (activity == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "活动不存在");
        requireOwned(ownerId, activityId);

        List<Vol> volunteers = Optional.ofNullable(volMapper.selectUsers(activityId)).orElseGet(List::of);
        int max = props.getBatchRewardMaxVolunteers();
        if (max > 0 && volunteers.size() > max)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "待答谢人数超过单次上限 " + max);

        int reward = activity.getVolunteerReward() == null ? 0 : activity.getVolunteerReward();
        long pending = volunteers.stream().filter(v -> v.getStatus() == null || v.getStatus() != 1).count();
        long totalCost = reward * pending;
        BigInteger cap = props.getBatchRewardTotalCap();
        if (cap != null && cap.signum() > 0 && BigInteger.valueOf(totalCost).compareTo(cap) > 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批量答谢合计超过上限 " + cap);
        Long balance = jdbc.queryForObject("select coin_balance from `user` where id=?", Long.class, ownerId);
        if (balance == null || balance < totalCost)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "余额不足，本次需 " + totalCost);

        String jobId = UUID.randomUUID().toString();
        jdbc.update("insert into batch_reward_job(job_id,owner_id,activity_id,state,total) values(?,?,?,'QUEUED',?)",
                jobId, ownerId, activityId, (int) pending);
        String owner = ownerId.toString();
        executor.submit(() -> run(jobId, activityId, owner));
        return jobId;
    }

    /** 后台逐笔结算；单笔失败记入 result_json，不影响其余。 */
    private void run(String jobId, Integer activityId, String owner) {
        jdbc.update("update batch_reward_job set state='RUNNING' where job_id=?", jobId);
        int ownerId = Integer.parseInt(owner);
        List<Map<String, String>> failures = new ArrayList<>();
        int done = 0;
        for (Vol vol : Optional.ofNullable(volMapper.selectUsers(activityId)).orElseGet(List::of)) {
            if (vol.getStatus() != null && vol.getStatus() == 1) continue;
            org.example.pojo.VolActivity va = new org.example.pojo.VolActivity();
            va.setUserId(vol.getId()); va.setActivityId(activityId); va.setStatus((short) 1);
            try {
                userService.updateVolActivity(va, ownerId);
                done++;
            } catch (Exception e) {
                Map<String, String> f = new HashMap<>();
                f.put("userId", String.valueOf(vol.getId()));
                f.put("reason", e.getMessage() == null ? "失败" : e.getMessage());
                failures.add(f);
                log.warn("异步批量答谢单笔失败 job={} userId={}", jobId, vol.getId(), e);
            }
            jdbc.update("update batch_reward_job set done=?,failed=? where job_id=?", done, failures.size(), jobId);
        }
        String result = failures.isEmpty() ? "{}" : failures.toString();
        jdbc.update("update batch_reward_job set state='DONE',result_json=?,finished_at=CURRENT_TIMESTAMP where job_id=?",
                result, jobId);
    }

    /** 查询进度（仅作业 owner 可见）。 */
    public Map<String, Object> progress(Integer actor, String jobId) {
        var rows = jdbc.queryForList("select owner_id,total,done,failed,state,result_json from batch_reward_job where job_id=?", jobId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作业不存在");
        Map<String, Object> row = rows.get(0);
        if (!actor.equals(row.get("owner_id"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看他人作业");
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("jobId", jobId);
        view.put("state", row.get("state"));
        view.put("total", row.get("total"));
        view.put("done", row.get("done"));
        view.put("failed", row.get("failed"));
        view.put("result", row.get("result_json"));
        return view;
    }

    private void requireOwned(Integer ownerId, Integer activityId) {
        Integer oldId = oldMapper.selectOldId(ownerId);
        Integer activityOldId = activityMapper.selectByActId(activityId) == null
                ? null : activityMapper.selectByActId(activityId).getOldId();
        if (oldId == null || activityOldId == null || !oldId.equals(activityOldId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能答谢本人发布的活动");
    }
}
