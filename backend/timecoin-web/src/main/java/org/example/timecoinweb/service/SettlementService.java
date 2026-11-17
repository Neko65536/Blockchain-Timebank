package org.example.timecoinweb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.example.pojo.Activity;
import org.example.pojo.VolActivity;
import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.mapper.ActivityMapper;
import org.example.timecoinweb.mapper.VolMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** 数据库保存事实，MQ只负责唤醒。所有重试都复用结算编号和原签名交易。 */
@Service
public class SettlementService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TimeCoinChainService chain;
    private final CoinBalanceLedgerService ledger;
    private final ActivityMapper activities;
    private final VolMapper volunteers;
    private final BlockchainProperties blockchain;
    private final ObjectMapper json;
    private final org.example.timecoinweb.config.ShardRouting routing;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<GovernanceService> governance;
    @Value("${settlement.max-attempts:8}") private int maxAttempts;
    @Value("${settlement.lease-ms:180000}") private long leaseMs;
    @Value("${settlement.retry-ms:5000}") private long retryMs;

    public SettlementService(JdbcTemplate jdbc, PlatformTransactionManager manager, TimeCoinChainService chain,
                             CoinBalanceLedgerService ledger, ActivityMapper activities, VolMapper volunteers,
                             BlockchainProperties blockchain, ObjectMapper json, org.example.timecoinweb.config.ShardRouting routing) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager); this.chain = chain;
        this.ledger = ledger; this.activities = activities; this.volunteers = volunteers;
        this.blockchain = blockchain; this.json = json;
        this.routing = routing;
    }

    public Map<String, Object> submitTransfer(int owner, String requestId, String type, String from, String to, BigInteger amount) {
        if (!List.of("USER_TRANSFER", "ADMIN_TRANSFER", "ADMIN_MINT").contains(type)) throw new IllegalArgumentException("未知结算类型");
        requireAmount(amount);
        String target = requireAccount(to);
        String source = "ADMIN_MINT".equals(type) ? null : requireAccount(from);
        if (Objects.equals(source, target)) throw new IllegalArgumentException("不能转账给自己");
        if ("USER_TRANSFER".equals(type) && !String.valueOf(owner).equals(source)) throw new IllegalArgumentException("付款人不匹配");
        String fingerprint = Hash.sha3String(type + ":" + source + ":" + target + ":" + amount);
        return transaction.execute(tx -> {
            lockOwner(owner);
            Order previous = findRequest(requestKey(owner, type, requestId), fingerprint);
            if (previous != null) return view(previous);
            requireReady();
            requireExistingAccount(target);
            if (source != null) requireExistingAccount(source);
            return view(insert(owner, requestKey(owner, type, requestId), fingerprint, type, source, target, amount, null, null));
        });
    }

    public Map<String, Object> submitPublish(Activity activity, int owner, String requestId) {
        String fingerprint;
        try {
            // 只对客户端可编辑字段生成摘要，排除服务器时间、主键和审核字段。
            Map<String, Object> fields = new TreeMap<>(json.convertValue(activity, Map.class));
            for (String field : List.of("id", "oldId", "administratorId", "createTime", "updateTime", "remain", "status",
                    "volSign", "volActStatus", "volRewardPaid")) fields.remove(field);
            fingerprint = Hash.sha3String(json.writeValueAsString(fields));
        } catch (Exception e) { throw new IllegalArgumentException("活动参数不能序列化", e); }
        final String payload = fingerprint;
        return transaction.execute(tx -> {
            lockOwner(owner);
            String key = requestKey(owner, "PUBLISH_FEE", requestId);
            Order previous = findRequest(key, payload);
            if (previous != null) return view(previous);
            BigInteger fee = blockchain.isEnabled() ? blockchain.getOldPublishActivityCost() : BigInteger.ZERO;
            if (fee == null || fee.signum() < 0) throw new IllegalArgumentException("发布费用配置错误");
            if (fee.signum() > 0) { requireReady(); requireAmount(fee); }
            String recipient = requireAccount(blockchain.getFeeRecipientUserId());
            requireExistingAccount(recipient);
            activity.setId(null);
            activity.setStatus(fee.signum() > 0 ? (short) 0 : (short) 1);
            activities.insert(activity);
            return view(insert(owner, key, payload, "PUBLISH_FEE", String.valueOf(owner), recipient, fee, activity.getId(), null));
        });
    }

    /** 调用方已经按活动→报名顺序加锁；加入同一个事务。 */
    public Map<String, Object> submitReward(Activity activity, VolActivity volunteer, int owner) {
        String key = requestKey(owner, "VOLUNTEER_REWARD", "activity:" + activity.getId() + ":vol:" + volunteer.getVolId());
        BigInteger amount = BigInteger.valueOf(activity.getVolunteerReward());
        String payload = Hash.sha3String(owner + ":" + volunteer.getUserId() + ":" + amount);
        Order previous = findRequest(key, payload);
        if (previous != null) return view(previous);
        requireReady(); requireAmount(amount);
        return view(insert(owner, key, payload, "VOLUNTEER_REWARD", String.valueOf(owner), String.valueOf(volunteer.getUserId()),
                amount, activity.getId(), volunteer.getVolId()));
    }

    public boolean hasReward(int activityId, int volId) {
        return jdbc.queryForObject("select count(*) from settlement_order where "+routing.all("created_at")+" and biz_type='VOLUNTEER_REWARD' and activity_id=? and volunteer_id=?",
                Integer.class, activityId, volId) > 0;
    }

    public Map<String, Object> get(String id, int viewer, boolean admin) {
        Order order = load(id);
        if (!admin && order.ownerId != viewer) throw new IllegalStateException("无权查看该结算");
        return view(order);
    }

    public void requireActivityMutable(Activity activity) {
        if (activity == null) throw new IllegalArgumentException("活动不存在");
        int count = jdbc.queryForObject("select count(*) from settlement_order where "+routing.all("created_at")+" and activity_id=? and "
                        + "(biz_type='VOLUNTEER_REWARD' or state<>'SUCCEEDED')", Integer.class, activity.getId());
        if (activity.getStatus() == 0 || count > 0) throw new IllegalStateException("该活动关联结算，不能修改或删除，请核查结算状态");
    }

    public List<Map<String, Object>> list(int viewer, boolean admin) {
        String where = " where "+routing.all("created_at")+(admin ? "" : " and owner_id=?");
        List<String> ids = admin ? jdbc.queryForList("select id from settlement_order"+where+" order by created_at desc limit 100", String.class)
                : jdbc.queryForList("select id from settlement_order" + where + " order by created_at desc limit 100", String.class, viewer);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : ids) result.add(view(load(id)));
        return result;
    }

    public Map<String, Object> retry(String id) {
        return transaction.execute(tx -> {
            jdbc.queryForList("select id from settlement_order where "+routing.at("order",id)+" and id=? for update", id);
            Order order = load(id);
            if ("REVIEW".equals(order.state)) {
                jdbc.update("update settlement_order set state='RETRY',attempts=0,next_attempt=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP where "+routing.at("order",id)+" and id=?", id);
                jdbc.update("update settlement_outbox set next_send=CURRENT_TIMESTAMP where "+routing.at("order",id)+" and order_id=?", id);
            }
            return view(load(id));
        });
    }

    /** 不持有业务事务等待RPC；过期租约可恢复，旧消费者不能提交新消费者的结果。 */
    public void process(String id) {
        String lease = UUID.randomUUID().toString();
        int claimed = jdbc.update("update settlement_order set state='PROCESSING',lease_token=?,lease_until=?,attempts=attempts+1,updated_at=CURRENT_TIMESTAMP "
                        + "where "+routing.at("order",id)+" and id=? and ((state in ('PENDING','RETRY') and next_attempt<=CURRENT_TIMESTAMP) or (state='PROCESSING' and lease_until<CURRENT_TIMESTAMP))",
                lease, Timestamp.from(Instant.now().plusMillis(leaseMs)), id);
        if (claimed == 0) return;
        Order order = load(id);
        try {
            String ref = "VOLUNTEER_REWARD".equals(order.bizType) ? "activity:" + order.activityId + ",vol:" + order.volunteerId : order.id;
            String hash = "ADMIN_MINT".equals(order.bizType)
                    ? chain.confirmMint(order.toUser, BigInteger.valueOf(order.amount), order.bizType, ref)
                    : chain.confirmTransfer(order.fromUser, order.toUser, BigInteger.valueOf(order.amount), order.bizType, ref);
            transaction.executeWithoutResult(tx -> {
                // 与报名操作统一锁顺序，防止支付入账与取消/状态修改交错。
                Activity activity = order.activityId == null ? null : activities.lockById(order.activityId);
                VolActivity volunteer = order.volunteerId == null ? null : volunteers.lockVolunteerActivityRow(order.activityId, order.volunteerId);
                jdbc.queryForList("select id from settlement_order where "+routing.at("order",id)+" and id=? for update", id);
                Order current = load(id);
                if (!"PROCESSING".equals(current.state) || !lease.equals(current.leaseToken)) return;
                if (order.activityId != null && activity == null) throw new IllegalStateException("结算活动不存在");
                if (order.volunteerId != null) {
                    if (volunteer == null || !Objects.equals(activity.getVolunteerReward(), Math.toIntExact(order.amount)))
                        throw new IllegalStateException("报名或答谢金额已变化，请人工核查");
                    volunteer.setStatus((short) 1); volunteer.setRewardPaid((short) 1);
                    volunteer.setUpdateTime(java.time.LocalDateTime.now());
                    volunteers.update(volunteer);
                }
                if ("ADMIN_MINT".equals(order.bizType)) ledger.recordMint(order.toUser, BigInteger.valueOf(order.amount), hash, order.bizType, ref);
                else ledger.recordTransfer(order.fromUser, order.toUser, BigInteger.valueOf(order.amount), hash, order.bizType, ref);
                if ("PUBLISH_FEE".equals(order.bizType)) {
                    if (activity.getStatus() != 0) throw new IllegalStateException("待扣费活动状态异常");
                    jdbc.update("update activity set status=1,update_time=CURRENT_TIMESTAMP where id=?", order.activityId);
                }
                jdbc.update("update settlement_order set state='SUCCEEDED',tx_hash=?,last_error=null,lease_token=null,lease_until=null,updated_at=CURRENT_TIMESTAMP where "+routing.at("order",id)+" and id=?", hash, id);
                if(blockchain.isGovernanceEnabled() && "ADMIN_MINT".equals(order.bizType)) governance.getObject().completeMint(id,hash);
            });
        } catch (Exception failure) {
            String error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            long delay = Math.min(300000L, retryMs * (1L << Math.min(order.attempts - 1, 6)));
            jdbc.update("update settlement_order set state=?,last_error=?,next_attempt=?,lease_token=null,lease_until=null,updated_at=CURRENT_TIMESTAMP where "+routing.at("order",id)+" and id=? and lease_token=?",
                    order.attempts >= maxAttempts ? "REVIEW" : "RETRY", error.substring(0, Math.min(500, error.length())),
                    Timestamp.from(Instant.now().plusMillis(delay)), id, lease);
        }
    }

    private Order insert(int owner, String key, String payload, String type, String from, String to, BigInteger amount, Integer activity, Integer volunteer) {
        String id = UUID.randomUUID().toString();
        boolean multi=blockchain.isGovernanceEnabled() && "ADMIN_MINT".equals(type);
        String state = amount.signum() == 0 ? "SUCCEEDED" : multi ? "MULTISIG" : "PENDING";
        Timestamp created=routing.now();
        routing.register("order",id,created);routing.register("order-request",key,created);
        jdbc.update("insert into settlement_order(id,request_key,payload_hash,owner_id,biz_type,from_user,to_user,amount,activity_id,volunteer_id,state,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, key, payload, owner, type, from, to, amount.longValueExact(), activity, volunteer, state,created);
        if(multi) governance.getObject().createMint(id,owner,to,amount.longValueExact());
        else if (amount.signum() > 0) jdbc.update("insert into settlement_outbox(order_id,created_at) values (?,?)", id,created);
        return load(id);
    }

    private void lockOwner(int owner) {
        if (jdbc.queryForList("select id from `user` where id=? for update", owner).isEmpty()) throw new IllegalArgumentException("用户不存在");
    }
    private Order findRequest(String key, String payload) {
        List<String> ids = jdbc.queryForList("select id from settlement_order where "+routing.at("order-request",key)+" and request_key=?", String.class, key);
        if (ids.isEmpty()) return null;
        Order previous = load(ids.get(0));
        if (!previous.payloadHash.equals(payload)) throw new IllegalArgumentException("同一请求编号不能修改业务参数");
        return previous;
    }
    private String requestKey(int owner, String type, String requestId) {
        if (requestId == null || !requestId.matches("[a-zA-Z0-9:_-]{8,100}")) throw new IllegalArgumentException("请提供8至100位Idempotency-Key请求编号");
        return Hash.sha3String(owner + ":" + type + ":" + requestId);
    }
    private void requireReady() { if (!chain.isChainReady()) throw new IllegalStateException("区块链未就绪，无法受理付费结算"); }
    private void requireAmount(BigInteger amount) {
        if (amount == null || amount.signum() <= 0 || amount.bitLength() > 63) throw new IllegalArgumentException("金额必须为正数且不超过台账范围");
    }
    private String requireAccount(String value) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 64) throw new IllegalArgumentException("账户编号无效");
        String normalized = value.trim();
        if (Objects.equals(blockchain.getFeeRecipientUserId(), normalized)) return normalized;
        try {
            int id = Integer.parseInt(normalized);
            if (id > 0 && String.valueOf(id).equals(normalized)) return normalized;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("用户编号必须为规范的正整数，不能带前导零");
    }
    private void requireExistingAccount(String account) {
        if (Objects.equals(blockchain.getFeeRecipientUserId(), account)) return;
        try {
            if (jdbc.queryForObject("select count(*) from `user` where id=?", Integer.class, Integer.parseInt(account)) == 1) return;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("收付款账户不存在");
    }
    Order load(String id) {
        List<Order> rows = jdbc.query("select * from settlement_order where "+routing.at("order",id)+" and id=?", (rs, n) -> {
            Order o = new Order(); o.id = rs.getString("id"); o.payloadHash = rs.getString("payload_hash"); o.ownerId = rs.getInt("owner_id");
            o.bizType = rs.getString("biz_type"); o.fromUser = rs.getString("from_user"); o.toUser = rs.getString("to_user"); o.amount = rs.getLong("amount");
            o.activityId = (Integer) rs.getObject("activity_id"); o.volunteerId = (Integer) rs.getObject("volunteer_id");
            o.state = rs.getString("state"); o.attempts = rs.getInt("attempts"); o.leaseToken = rs.getString("lease_token");
            o.txHash = rs.getString("tx_hash"); o.lastError = rs.getString("last_error"); return o;
        }, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("结算不存在");
        return rows.get(0);
    }
    private Map<String, Object> view(Order o) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settlementId", o.id); result.put("state", o.state); result.put("bizType", o.bizType);
        result.put("activityId", o.activityId); result.put("amount", String.valueOf(o.amount));
        result.put("txHash", o.txHash); result.put("attempts", o.attempts); result.put("lastError", o.lastError);
        return result;
    }
    @Data
    static class Order {
        String id, payloadHash, bizType, fromUser, toUser, state, leaseToken, txHash, lastError;
        int ownerId, attempts; long amount; Integer activityId, volunteerId;
    }
}
