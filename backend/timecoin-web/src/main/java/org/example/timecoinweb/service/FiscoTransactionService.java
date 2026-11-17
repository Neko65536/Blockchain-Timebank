package org.example.timecoinweb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.config.ShardRouting;
import org.example.timecoinweb.signer.RemoteSigner;
import org.fisco.bcos.sdk.jni.utilities.tx.TransactionStructBuilderJniObj;
import org.fisco.bcos.sdk.jni.utilities.tx.TransactionData;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.model.TransactionReceipt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import java.util.*;

/** FISCO随机nonce包含在原签名凭据中；恢复只重播原交易，禁止过期后自动重新签名。 */
@Service
@ConditionalOnProperty(name="blockchain.backend", havingValue="fisco")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${blockchain.enabled:false}")
public class FiscoTransactionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;
    private final ShardRouting routing;
    private final BlockchainProperties properties;
    private final Client client;
    private final RemoteSigner signer;
    private final ObjectMapper json;

    public FiscoTransactionService(JdbcTemplate jdbc, PlatformTransactionManager manager, ShardRouting routing,
            BlockchainProperties properties, Client client, RemoteSigner signer, ObjectMapper json) {
        this.jdbc=jdbc; this.routing=routing; this.properties=properties; this.client=client; this.signer=signer; this.json=json;
        this.independent=new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public String send(String data, String businessKey) throws Exception {
        String key=Hash.sha3String("business:"+(businessKey==null?UUID.randomUUID():businessKey));
        String payload=Hash.sha3String(properties.getContractAddress().toLowerCase(Locale.ROOT)+":"+data);
        String scope=scope();
        // 只锁同一业务键，不将无关FISCO交易按账户nonce串行化。
        try {independent.executeWithoutResult(tx->jdbc.update("insert into chain_signer(scope_key) values (?)",key));}
        catch(org.springframework.dao.DuplicateKeyException exists) { /* 永久幂等锁记录。 */ }
        Submission saved=independent.execute(tx->{
            jdbc.queryForList("select scope_key from chain_signer where scope_key=? for update",key);
            List<Submission> rows=jdbc.query("select * from chain_submission where "+routing.all("created_at")+" and request_key=?",
                    (rs,n)->new Submission(rs.getString("tx_hash"),rs.getString("raw_tx"),rs.getString("state"),rs.getString("scope_key"),rs.getString("payload_hash")),key);
            if(!rows.isEmpty()) {
                Submission old=rows.get(0);
                if(!scope.equals(old.scope)||!payload.equals(old.payload)) throw new IllegalStateException("原交易的链、群组、签名账户或参数已变化");
                return old;
            }
            try {
                long limit=client.getBlockLimit().longValueExact();
                JsonNode signed=signer.sign(client.getChainId(),client.getGroup(),properties.getContractAddress(),data,limit);
                String hash=signed.path("txHash").asText();
                String raw=signed.path("rawTx").asText();
                verify(signed,data,limit);
                // envelope使恢复入口能识别协议，不将FISCO交易错误发向以太坊。
                String envelope=json.writeValueAsString(Map.of("protocol","fisco","rawTx",raw,"blockLimit",limit));
                java.sql.Timestamp created=routing.now();
                routing.register("chain-request",key,created); routing.register("chain-hash",hash,created);
                jdbc.update("insert into chain_submission(request_key,scope_key,nonce_value,payload_hash,tx_hash,raw_tx,state,created_at) values (?,?,NULL,?,?,?,'PREPARED',?)",
                        key,scope,payload,hash,envelope,created);
                return new Submission(hash,envelope,"PREPARED",scope,payload);
            } catch(Exception failure) {throw new IllegalStateException("FISCO签名或持久化未完成，未广播交易",failure);}
        });
        return confirm(saved);
    }

    private void verify(JsonNode signed,String data,long limit) throws Exception {
        org.fisco.bcos.sdk.jni.utilities.tx.Transaction decoded=TransactionStructBuilderJniObj.decodeTransactionStruct(signed.path("rawTx").asText());
        TransactionData tx=decoded.getTransactionData();
        if(tx==null || tx.getVersion()!=0 || decoded.getAttribute()!=0 || tx.getBlockLimit()!=limit
                || !client.getChainId().equals(tx.getChainId()) || !client.getGroup().equals(tx.getGroupId())
                || !Numeric.cleanHexPrefix(properties.getContractAddress()).equalsIgnoreCase(Numeric.cleanHexPrefix(tx.getTo()))
                || !Arrays.equals(Numeric.hexStringToByteArray(data),tx.getInput())
                || !Numeric.cleanHexPrefix(signed.path("txHash").asText()).equalsIgnoreCase(Numeric.cleanHexPrefix(tx.calculateHash(0)))
                || !properties.getSignerAddress().equalsIgnoreCase(signed.path("signer").asText()))
            throw new IllegalStateException("签名交易与原请求不符");
    }

    public String resume(String hash) throws Exception {
        List<Submission> rows=jdbc.query("select * from chain_submission where "+routing.at("chain-hash",hash)+" and tx_hash=?",
                (rs,n)->new Submission(rs.getString("tx_hash"),rs.getString("raw_tx"),rs.getString("state"),rs.getString("scope_key"),rs.getString("payload_hash")),hash);
        if(rows.isEmpty()) throw new IllegalArgumentException("未找到原交易");
        return confirm(rows.get(0));
    }

    private String confirm(Submission saved) throws Exception {
        if(!scope().equals(saved.scope)) throw new IllegalStateException("请恢复原交易的链、群组和签名账户配置");
        JsonNode envelope=json.readTree(saved.raw);
        if(!"fisco".equals(envelope.path("protocol").asText())) throw new IllegalStateException("交易协议不匹配");
        if("CONFIRMED".equals(saved.state)) return saved.hash;
        if("FAILED".equals(saved.state)) throw new IllegalStateException("原交易执行失败，需人工核查："+saved.hash);
        int attempts=properties.getReceiptAttempts();long interval=properties.getReceiptPollIntervalMs();
        if(attempts<1 || interval<0) throw new IllegalStateException("回执轮询配置不合法");
        for(int i=0;i<attempts;i++) {
            TransactionReceipt receipt;
            try {receipt=client.getTransactionReceipt(saved.hash,false).getTransactionReceipt();}
            catch(org.fisco.bcos.sdk.v3.client.exceptions.ClientException queryFailure) {
                // 3.8.0对未知哈希与存储故障共用查询异常，不能据此断言“未执行”。
                // 首轮仅允许重播已落库的同一签名（同一哈希），绝不重签或记为失败。
                if(i>0) throw new IllegalStateException("FISCO原交易查询失败，结果待核查："+saved.hash,queryFailure);
                receipt=null;
            }
            if(receipt!=null) return accept(saved.hash,receipt);
            if(i==0) {
                if(client.getBlockNumber().getBlockNumber().longValueExact()>=envelope.path("blockLimit").asLong())
                    throw new IllegalStateException("原交易已超过区块有效期且未查到回执；保留凭据，须人工核查，禁止自动重签："+saved.hash);
                // 同步SDK发送返回回执；超时或异常不会变更为FAILED，恢复仍查询原哈希。
                receipt=client.sendTransaction(envelope.path("rawTx").asText(),false).getTransactionReceipt();
                if(receipt!=null) return accept(saved.hash,receipt);
                state(saved.hash,"SUBMITTED");
            }
            if(i+1<attempts) {
                try {Thread.sleep(interval);} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw interrupted;}
            }
        }
        throw new IllegalStateException("FISCO回执待查，勿另建转账："+saved.hash);
    }

    private String accept(String hash,TransactionReceipt receipt) {
        if(!hash.equalsIgnoreCase(receipt.getTransactionHash()) || receipt.getBlockNumber()==null
                || !properties.getContractAddress().equalsIgnoreCase(receipt.getTo())
                || !properties.getSignerAddress().equalsIgnoreCase(receipt.getFrom())) throw new IllegalStateException("FISCO回执与原交易不匹配");
        state(hash,receipt.getStatus()==0?"CONFIRMED":"FAILED");
        if(receipt.getStatus()!=0) throw new IllegalStateException("FISCO交易执行失败："+hash);
        return hash;
    }

    private void state(String hash,String state) {
        independent.executeWithoutResult(tx->jdbc.update("update chain_submission set state=? where "+routing.at("chain-hash",hash)+" and tx_hash=? and state not in ('CONFIRMED','FAILED')",state,hash));
    }

    private String scope() {return Hash.sha3String("fisco:"+client.getChainId()+":"+client.getGroup()+":"+properties.getSignerAddress().toLowerCase(Locale.ROOT));}
    private static final class Submission {
        final String hash,raw,state,scope,payload;
        Submission(String hash,String raw,String state,String scope,String payload) {this.hash=hash;this.raw=raw;this.state=state;this.scope=scope;this.payload=payload;}
    }
}
