package org.example.timecoinweb.controller;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.example.pojo.Result;
import org.example.timecoinweb.service.CoinBalanceLedgerService;
import org.example.timecoinweb.service.TimeCoinChainAdminService;
import org.example.timecoinweb.service.TimeCoinChainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * 管理员链上操作（路径须含 administrator 以通过 {@link org.example.timecoinweb.interceptor.LoginCheckInterceptor}）。
 */
@Slf4j
@RestController
@RequestMapping("/administrator/chain")
public class ChainController {

    @Autowired
    private TimeCoinChainService timeCoinChainService;

    @Autowired
    private TimeCoinChainAdminService timeCoinChainAdminService;

    @Autowired
    private CoinBalanceLedgerService coinBalanceLedgerService;

    @Autowired
    private org.example.timecoinweb.service.ChainTransactionService chainTransactionService;
    @Autowired
    private org.example.timecoinweb.service.SettlementService settlements;
    @Autowired
    private org.example.timecoinweb.service.GovernanceService governance;
    @Autowired
    private org.example.timecoinweb.config.BlockchainProperties blockchain;

    @GetMapping("/governance")
    public Result proposals() { return Result.success(governance.list()); }

    @PostMapping("/governance/freeze")
    public Result freeze(@RequestBody FreezeBody body,@RequestHeader("token") String token,@RequestHeader("Idempotency-Key") String request) {
        if(body==null || body.frozen==null) throw new IllegalArgumentException("必须指定账户和冻结状态");
        return Result.success(governance.proposeFreeze((Integer)org.example.utils.JwtUtils.parseJWT(token).get("id"),request,body.userId,body.frozen));
    }

    @PostMapping("/governance/{id}/approve")
    public Result approve(@PathVariable String id,@RequestBody ApprovalBody body,@RequestHeader("token") String token) {
        return Result.success(governance.approve(id,body.signature,(Integer)org.example.utils.JwtUtils.parseJWT(token).get("id")));
    }

    @PostMapping("/governance/{id}/execute")
    public Result execute(@PathVariable String id) throws Exception {return Result.success(governance.executeFreeze(id));}

    @Data public static class FreezeBody {private String userId;private Boolean frozen;}
    @Data public static class ApprovalBody {private String signature;}

    @GetMapping("/settlements")
    public Result settlements() { return Result.success(settlements.list(0, true)); }

    @PostMapping("/settlements/{id}/retry")
    public Result retrySettlement(@PathVariable String id) { return Result.success(settlements.retry(id)); }

    @GetMapping("/transactions")
    public Result transactions() {
        return Result.success(chainTransactionService.listRecent());
    }

    @PostMapping("/transactions/{txHash}/resume")
    public Result resumeTransaction(@PathVariable String txHash) throws Exception {
        String confirmedHash = chainTransactionService.resume(txHash);
        return Result.success(Map.of("txHash", confirmedHash, "message", "链上已确认；P1订单由后台继续入账，REVIEW订单需恢复原结算；历史P0交易请核查台账，勿另建转账"));
    }

    /**
     * 时间币管理页：链状态、各用户链上余额、最近 Mint/Transfer 事件。
     *
     * @param eventLimit 最多返回事件条数（1–500）
     */
    @GetMapping("/overview")
    public Result overview(@RequestParam(defaultValue = "100") int eventLimit) {
        int lim = Math.max(1, Math.min(eventLimit, 500));
        return Result.success(timeCoinChainAdminService.overview(lim));
    }

    @GetMapping("/status")
    public Result status() {
        Map<String, Object> m = new HashMap<>();
        m.put("ready", timeCoinChainService.isChainReady());
        m.put("governanceEnabled", blockchain.isGovernanceEnabled());
        if (!timeCoinChainService.isChainReady()) {
            m.put("reason", timeCoinChainService.getNotReadyReason());
        }
        return Result.success(m);
    }

    @GetMapping("/balance")
    public Result balance(@RequestParam String userId) {
        try {
            BigInteger balance = timeCoinChainService.balanceOf(userId);
            Map<String, Object> m = new HashMap<>();
            m.put("userId", userId);
            m.put("balance", balance.toString());
            return Result.success(m);
        } catch (Exception e) {
            log.warn("balanceOf failed", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/mint")
    public Result mint(@RequestBody MintBody body, @RequestHeader("token") String token,
                       @RequestHeader("Idempotency-Key") String requestId) {
        try {
            if (body == null || body.getUserId() == null || body.getAmount() == null) {
                return Result.error("请提供 userId 与 amount");
            }
            BigInteger amount = new BigInteger(body.getAmount().trim());
            int owner = (Integer) org.example.utils.JwtUtils.parseJWT(token).get("id");
            return Result.success(settlements.submitTransfer(owner, requestId, "ADMIN_MINT", null, body.getUserId(), amount));
        } catch (Exception e) {
            log.warn("mint failed", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/transfer")
    public Result transfer(@RequestBody TransferBody body, @RequestHeader("token") String token,
                           @RequestHeader("Idempotency-Key") String requestId) {
        try {
            if (body == null || body.getFromUserId() == null || body.getToUserId() == null || body.getAmount() == null) {
                return Result.error("请提供 fromUserId、toUserId、amount");
            }
            BigInteger amount = new BigInteger(body.getAmount().trim());
            int owner = (Integer) org.example.utils.JwtUtils.parseJWT(token).get("id");
            return Result.success(settlements.submitTransfer(owner, requestId, "ADMIN_TRANSFER", body.getFromUserId(), body.getToUserId(), amount));
        } catch (Exception e) {
            log.warn("transfer failed", e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 手动触发链上余额与数据库镜像对账。
     *
     * @param autoFix 是否修正余额；写入修正仅限暂停交易后的维护窗口。
     */
    @PostMapping("/reconcile")
    public Result reconcile(@RequestParam(defaultValue = "false") boolean autoFix) {
        try {
            return Result.success(coinBalanceLedgerService.reconcileAll(autoFix));
        } catch (Exception e) {
            log.warn("reconcile failed", e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 最近对账记录与链上交易台账。
     */
    @GetMapping("/reconcile/logs")
    public Result reconcileLogs(@RequestParam(defaultValue = "20") int limit) {
        Map<String, Object> body = new HashMap<>();
        body.put("reconcileLogs", coinBalanceLedgerService.listRecentReconcileLogs(limit));
        body.put("txLogs", coinBalanceLedgerService.listRecentTxLogs(limit));
        return Result.success(body);
    }

    @Data
    public static class MintBody {
        private String userId;
        /** 十进制整数字符串，如 "100" */
        private String amount;
    }

    @Data
    public static class TransferBody {
        private String fromUserId;
        private String toUserId;
        private String amount;
    }
}
