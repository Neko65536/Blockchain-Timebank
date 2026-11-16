package org.example.timecoinweb.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pojo.ChainAccountMirror;
import org.example.pojo.ChainReconcileLog;
import org.example.pojo.ChainTxLog;
import org.example.pojo.User;
import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.mapper.ChainAccountMirrorMapper;
import org.example.timecoinweb.mapper.ChainReconcileLogMapper;
import org.example.timecoinweb.mapper.ChainTxLogMapper;
import org.example.timecoinweb.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 时间币余额台账：链上交易成功后同步更新数据库镜像，并支持定期与链上对账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoinBalanceLedgerService {

    private final UserMapper userMapper;
    private final ChainAccountMirrorMapper chainAccountMirrorMapper;
    private final ChainTxLogMapper chainTxLogMapper;
    private final ChainReconcileLogMapper chainReconcileLogMapper;
    private final TimeCoinChainService timeCoinChainService;
    private final BlockchainProperties blockchainProperties;
    private final ObjectMapper objectMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final org.example.timecoinweb.config.ShardRouting routing;

    @Transactional(rollbackFor = Exception.class)
    public void recordMint(String userId, BigInteger amount, String txHash, String bizType, String bizRef) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        long amt = amount.longValueExact();
        if (!claimReceipt(txHash)) return;
        ChainTxLog txLog = new ChainTxLog();
        txLog.setTxType("MINT");
        txLog.setFromAccount("platform");
        txLog.setToAccount(userId);
        txLog.setAmount(amt);
        txLog.setTxHash(txHash);
        txLog.setBizType(bizType);
        txLog.setBizRef(bizRef);
        chainTxLogMapper.insert(txLog);
        creditAccount(userId, amt);
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordTransfer(String fromUserId, String toUserId, BigInteger amount, String txHash,
                               String bizType, String bizRef) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        long amt = amount.longValueExact();
        if (!claimReceipt(txHash)) return;
        ChainTxLog txLog = new ChainTxLog();
        txLog.setTxType("TRANSFER");
        txLog.setFromAccount(fromUserId);
        txLog.setToAccount(toUserId);
        txLog.setAmount(amt);
        txLog.setTxHash(txHash);
        txLog.setBizType(bizType);
        txLog.setBizRef(bizRef);
        chainTxLogMapper.insert(txLog);
        debitAccount(fromUserId, amt);
        creditAccount(toUserId, amt);
    }

    /**
     * 全量对账：逐用户及平台账户比对链上 balanceOf 与数据库镜像。
     *
     * @param autoFix true 时以链上余额为准修正数据库
     */
    public Map<String, Object> reconcileAll(boolean autoFix) {
        Map<String, Object> summary = new HashMap<>();
        List<Map<String, Object>> mismatches = new ArrayList<>();
        int totalChecked = 0;
        int fixedCount = 0;
        boolean chainReady = timeCoinChainService.isChainReady();
        summary.put("chainReady", chainReady);

        if (!chainReady) {
            summary.put("reason", timeCoinChainService.getNotReadyReason());
            summary.put("totalChecked", 0);
            summary.put("mismatchCount", 0);
            summary.put("fixedCount", 0);
            summary.put("mismatches", mismatches);
            persistReconcileLog(0, 0, 0, false, summary);
            return summary;
        }

        List<User> users = userMapper.selectAllForBalanceReconcile();
        if (users == null) {
            users = new ArrayList<>();
        }

        for (User user : users) {
            totalChecked++;
            String chainUserId = String.valueOf(user.getId());
            long dbBalance = user.getCoinBalance() != null ? user.getCoinBalance() : 0L;
            try {
                BigInteger chainBalance = timeCoinChainService.balanceOf(chainUserId);
                long chainVal = chainBalance.longValue();
                if (dbBalance != chainVal) {
                    Map<String, Object> row = mismatchRow(chainUserId, user.getUsername(), dbBalance, chainVal);
                    mismatches.add(row);
                    if (autoFix) {
                        userMapper.setCoinBalance(user.getId(), chainVal);
                        row.put("fixed", true);
                        fixedCount++;
                    } else {
                        row.put("fixed", false);
                    }
                }
            } catch (Exception e) {
                log.warn("对账 balanceOf 失败 userId={}", chainUserId, e);
                Map<String, Object> row = new HashMap<>();
                row.put("accountId", chainUserId);
                row.put("username", user.getUsername());
                row.put("error", e.getMessage());
                mismatches.add(row);
            }
        }

        String platformId = blockchainProperties.getFeeRecipientUserId();
        if (StringUtils.hasText(platformId)) {
            totalChecked++;
            ChainAccountMirror mirror = chainAccountMirrorMapper.selectById(platformId);
            if (mirror == null) {
                mirror = new ChainAccountMirror();
                mirror.setAccountId(platformId);
                mirror.setCoinBalance(0L);
                chainAccountMirrorMapper.insert(mirror);
            }
            long dbBalance = mirror.getCoinBalance() != null ? mirror.getCoinBalance() : 0L;
            try {
                BigInteger chainBalance = timeCoinChainService.balanceOf(platformId);
                long chainVal = chainBalance.longValue();
                if (dbBalance != chainVal) {
                    Map<String, Object> row = mismatchRow(platformId, "平台账户", dbBalance, chainVal);
                    mismatches.add(row);
                    if (autoFix) {
                        chainAccountMirrorMapper.setBalance(platformId, chainVal);
                        row.put("fixed", true);
                        fixedCount++;
                    } else {
                        row.put("fixed", false);
                    }
                }
            } catch (Exception e) {
                log.warn("对账 platform balanceOf 失败 accountId={}", platformId, e);
                Map<String, Object> row = new HashMap<>();
                row.put("accountId", platformId);
                row.put("username", "平台账户");
                row.put("error", e.getMessage());
                mismatches.add(row);
            }
        }

        summary.put("totalChecked", totalChecked);
        summary.put("mismatchCount", mismatches.size());
        summary.put("fixedCount", fixedCount);
        summary.put("mismatches", mismatches);
        summary.put("autoFix", autoFix);
        summary.put("runTime", LocalDateTime.now().toString());

        persistReconcileLog(totalChecked, mismatches.size(), fixedCount, true, summary);
        if (!mismatches.isEmpty()) {
            log.warn("时间币余额对账发现 {} 处不一致，已修正 {}", mismatches.size(), fixedCount);
        } else {
            log.info("时间币余额对账完成，共检查 {} 个账户，全部一致", totalChecked);
        }
        return summary;
    }

    private boolean claimReceipt(String txHash) {
        // 同一回执串行入账；标记与台账更新属于同一本地事务。
        java.util.List<java.util.Map<String, Object>> receipts = jdbc.queryForList(
                "select state, ledger_applied from chain_submission where "+routing.at("chain-hash",txHash)+" and tx_hash=? for update", txHash);
        if (receipts.isEmpty() || !"CONFIRMED".equals(receipts.get(0).get("state"))) {
            throw new IllegalStateException("交易尚未确认，禁止更新余额台账");
        }
        if (((Number) receipts.get(0).get("ledger_applied")).intValue() == 1) return false;
        jdbc.update("update chain_submission set ledger_applied=1 where "+routing.at("chain-hash",txHash)+" and tx_hash=?", txHash);
        return true;
    }

    public List<ChainReconcileLog> listRecentReconcileLogs(int limit) {
        return chainReconcileLogMapper.listRecent(Math.max(1, Math.min(limit, 100)));
    }

    public List<ChainTxLog> listRecentTxLogs(int limit) {
        return chainTxLogMapper.listRecent(Math.max(1, Math.min(limit, 200)));
    }

    private void creditAccount(String accountId, long amount) {
        Integer userId = parseUserId(accountId);
        if (userId != null) {
            if (userMapper.addCoinBalance(userId, amount) != 1) {
                throw new IllegalStateException("收款用户不存在，台账写入已回滚");
            }
            return;
        }
        ensureMirrorAccount(accountId);
        chainAccountMirrorMapper.addBalance(accountId, amount);
    }

    private void debitAccount(String accountId, long amount) {
        Integer userId = parseUserId(accountId);
        if (userId != null) {
            int affected = userMapper.deductCoinBalance(userId, amount);
            if (affected == 0) {
                throw new IllegalStateException("用户余额镜像不一致，请核对台账后恢复原交易");
            }
            return;
        }
        ensureMirrorAccount(accountId);
        int affected = chainAccountMirrorMapper.deductBalance(accountId, amount);
        if (affected == 0) {
            throw new IllegalStateException("平台余额镜像不一致，请核对台账后恢复原交易");
        }
    }

    private void ensureMirrorAccount(String accountId) {
        if (chainAccountMirrorMapper.selectById(accountId) == null) {
            ChainAccountMirror mirror = new ChainAccountMirror();
            mirror.setAccountId(accountId);
            mirror.setCoinBalance(0L);
            chainAccountMirrorMapper.insert(mirror);
        }
    }

    private static Integer parseUserId(String accountId) {
        if (!StringUtils.hasText(accountId)) {
            return null;
        }
        try {
            return Integer.parseInt(accountId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> mismatchRow(String accountId, String label, long dbBalance, long chainBalance) {
        Map<String, Object> row = new HashMap<>();
        row.put("accountId", accountId);
        row.put("username", label);
        row.put("dbBalance", String.valueOf(dbBalance));
        row.put("chainBalance", String.valueOf(chainBalance));
        row.put("diff", String.valueOf(chainBalance - dbBalance));
        return row;
    }

    private void persistReconcileLog(int totalChecked, int mismatchCount, int fixedCount,
                                     boolean chainReady, Map<String, Object> summary) {
        ChainReconcileLog logRow = new ChainReconcileLog();
        logRow.setRunTime(LocalDateTime.now());
        logRow.setTotalChecked(totalChecked);
        logRow.setMismatchCount(mismatchCount);
        logRow.setFixedCount(fixedCount);
        logRow.setChainReady((short) (chainReady ? 1 : 0));
        try {
            logRow.setDetailJson(objectMapper.writeValueAsString(summary));
        } catch (JsonProcessingException e) {
            logRow.setDetailJson("{\"serializeError\":\"" + e.getMessage() + "\"}");
        }
        chainReconcileLogMapper.insert(logRow);
    }
}
