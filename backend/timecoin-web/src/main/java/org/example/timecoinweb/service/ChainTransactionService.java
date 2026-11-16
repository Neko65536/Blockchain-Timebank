package org.example.timecoinweb.service;

import org.example.timecoinweb.config.BlockchainProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.web3j.crypto.*;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/** 持久化签名凭据；同一签名账户在数据库中串行分配nonce，RPC等待在锁外。 */
@Service
public class ChainTransactionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;
    private final BlockchainProperties properties;
    private final ObjectProvider<Web3j> clients;
    private final ObjectProvider<Credentials> credentials;

    public ChainTransactionService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                   BlockchainProperties properties, ObjectProvider<Web3j> clients,
                                   ObjectProvider<Credentials> credentials) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clients = clients;
        this.credentials = credentials;
        this.independent = new TransactionTemplate(manager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // 实例内串行确认降低节点压力；跨实例依靠chain_signer行锁分配nonce。
    public synchronized String send(String data, String businessKey) throws Exception {
        Web3j web3j = clients.getObject();
        Credentials signer = credentials.getObject();
        EthChainId chain = web3j.ethChainId().send();
        if (chain.hasError()) throw new IllegalStateException("无法读取链ID");
        long chainId = chain.getChainId().longValueExact();
        String scope = Hash.sha3String(chainId + ":" + signer.getAddress());
        String legacyKey = Hash.sha3String(scope + ":" + properties.getContractAddress().toLowerCase() + ":"
                + (businessKey == null ? UUID.randomUUID() : businessKey));
        String key = businessKey == null ? legacyKey : Hash.sha3String("business:" + businessKey);
        String payload = Hash.sha3String(properties.getContractAddress().toLowerCase() + ":" + data);
        EthGetTransactionCount count = web3j.ethGetTransactionCount(signer.getAddress(), DefaultBlockParameterName.PENDING).send();
        if (count.hasError()) throw new IllegalStateException("无法读取签名账户nonce");
        try {
            independent.executeWithoutResult(status -> jdbc.update("insert into chain_signer(scope_key) values (?)", scope));
        } catch (org.springframework.dao.DuplicateKeyException alreadyExists) { /* 数据库唯一键保证只有一条锁记录。 */ }
        Submission submission = independent.execute(status -> {
            jdbc.queryForList("select scope_key from chain_signer where scope_key=? for update", scope);
            List<Submission> previous = jdbc.query("select * from chain_submission where request_key in (?,?)", (rs, n) -> {
                if (!scope.equals(rs.getString("scope_key"))) throw new IllegalStateException("原结算的链或签名账户已变化，请恢复原配置");
                return new Submission(rs.getString("tx_hash"), rs.getString("raw_tx"), rs.getString("payload_hash"), rs.getString("state"));
            }, key, legacyKey);
            if (!previous.isEmpty()) {
                Submission saved = previous.get(0);
                if (!payload.equals(saved.payload)) throw new IllegalStateException("同一结算业务不能修改合约、收款人或金额");
                return saved;
            }
            BigInteger nonce = count.getTransactionCount();
            BigDecimal reserved = jdbc.queryForObject("select max(nonce_value) from chain_submission where scope_key=?", BigDecimal.class, scope);
            if (reserved != null) nonce = nonce.max(reserved.toBigIntegerExact().add(BigInteger.ONE));
            RawTransaction raw = RawTransaction.createTransaction(nonce, DefaultGasProvider.GAS_PRICE,
                    DefaultGasProvider.GAS_LIMIT, properties.getContractAddress(), BigInteger.ZERO, data);
            String signed = Numeric.toHexString(TransactionEncoder.signMessage(raw, chainId, signer));
            String hash = Hash.sha3(signed);
            BigDecimal storedNonce = new BigDecimal(nonce);
            // 必须先提交凭据再广播；外层业务回滚也不能丢失已签名交易。
            jdbc.update("insert into chain_submission "
                            + "(request_key,scope_key,nonce_value,payload_hash,tx_hash,raw_tx,state) values (?,?,?,?,?,?,'PREPARED')",
                    key, scope, storedNonce, payload, hash, signed);
            return new Submission(hash, signed, payload, "PREPARED");
        });
        if ("CONFIRMED".equals(submission.state)) return submission.hash;
        if ("FAILED".equals(submission.state)) throw new IllegalStateException("原交易执行失败，需管理员核查：" + submission.hash);
        return confirm(web3j, submission, true);
    }

    /** 仅由管理员入口调用；恢复保存的原交易，不生成新的签名或nonce。 */
    public synchronized String resume(String hash) throws Exception {
        List<Submission> rows = jdbc.query("select * from chain_submission where tx_hash=?", (rs, n) ->
                new Submission(rs.getString("tx_hash"), rs.getString("raw_tx"), rs.getString("payload_hash"), rs.getString("state")), hash);
        if (rows.isEmpty()) throw new IllegalArgumentException("未找到交易凭据");
        if ("CONFIRMED".equals(rows.get(0).state)) return hash;
        if ("FAILED".equals(rows.get(0).state)) throw new IllegalStateException("该交易已执行失败");
        return confirm(clients.getObject(), rows.get(0), true);
    }

    public List<java.util.Map<String, Object>> listRecent() {
        return jdbc.queryForList("select tx_hash,state,ledger_applied,nonce_value from chain_submission order by nonce_value desc limit 50");
    }

    private String confirm(Web3j web3j, Submission submission, boolean broadcast) throws Exception {
        try {
            return pollReceipt(web3j, submission, broadcast);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("节点通信失败，交易结果待查，请查询原交易，勿另建转账：" + submission.hash, e);
        }
    }

    private String pollReceipt(Web3j web3j, Submission submission, boolean broadcast) throws Exception {
        int attempts = properties.getReceiptAttempts();
        long interval = properties.getReceiptPollIntervalMs();
        if (attempts < 1 || interval < 0) throw new IllegalStateException("交易回执轮询配置不合法");
        for (int i = 0; i < attempts; i++) {
            EthGetTransactionReceipt response = web3j.ethGetTransactionReceipt(submission.hash).send();
            if (response.hasError()) throw new IllegalStateException("交易结果待查，请勿另建转账：" + submission.hash);
            if (response.getTransactionReceipt().isPresent()) {
                TransactionReceipt receipt = response.getTransactionReceipt().get();
                if (!submission.hash.equalsIgnoreCase(receipt.getTransactionHash())
                        || receipt.getBlockNumberRaw() == null || !StringUtils.hasText(receipt.getStatus())) {
                    throw new IllegalStateException("交易回执不完整，结果待查：" + submission.hash);
                }
                if (!receipt.isStatusOK()) {
                    setState(submission.hash, "FAILED");
                    throw new IllegalStateException("链上交易执行失败：" + submission.hash);
                }
                setState(submission.hash, "CONFIRMED");
                return submission.hash;
            }
            if (i == 0 && broadcast) {
                EthSendTransaction sent = web3j.ethSendRawTransaction(submission.raw).send();
                // already known/nonce too low也可能对应已接收的原交易；继续按原哈希查回执，不生成新nonce。
                if (!sent.hasError() && !submission.hash.equalsIgnoreCase(sent.getTransactionHash())) {
                    throw new IllegalStateException("节点返回的交易哈希不匹配：" + submission.hash);
                }
                if (!sent.hasError()) setState(submission.hash, "SUBMITTED");
            }
            if (i + 1 < attempts) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待回执被中断，交易结果待查：" + submission.hash, e);
                }
            }
        }
        throw new IllegalStateException("等待回执超时，交易结果待查；请查询原交易，勿另建转账：" + submission.hash);
    }

    private void setState(String hash, String state) {
        independent.executeWithoutResult(status -> jdbc.update("update chain_submission set state=? where tx_hash=? and state not in ('CONFIRMED','FAILED')", state, hash));
    }

    private static class Submission {
        final String hash, raw, payload, state;
        Submission(String hash, String raw, String payload, String state) {
            this.hash = hash; this.raw = raw; this.payload = payload; this.state = state;
        }
    }
}
