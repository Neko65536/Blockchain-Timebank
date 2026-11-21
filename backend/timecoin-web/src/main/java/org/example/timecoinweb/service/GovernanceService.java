package org.example.timecoinweb.service;

import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.config.ShardRouting;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.abi.*;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.*;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

/** 仅收集独立审批人的personal_sign签名，业务服务器不保管审批私钥。 */
@Service
public class GovernanceService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BlockchainProperties properties;
    private final ShardRouting routing;
    private final ObjectProvider<TimeCoinChainService> chain;
    private final ObjectProvider<ChainTransactionService> transactions;

    public GovernanceService(JdbcTemplate jdbc,PlatformTransactionManager manager,BlockchainProperties properties,ShardRouting routing,
                             ObjectProvider<TimeCoinChainService> chain,ObjectProvider<ChainTransactionService> transactions) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.properties=properties;this.routing=routing;this.chain=chain;this.transactions=transactions;
    }

    /** 调用方位于结算创建事务中；MULTISIG阶段不产生outbox。 */
    public void createMint(String settlement,int owner,String account,long amount) {
        var quota=jdbc.queryForMap("select * from mint_quota_admission where settlement_id=?",settlement);
        int action=((Number)quota.get("lifetime_review")).intValue()==1?3:1;
        create(settlement,Hash.sha3String("mint:"+settlement),settlement,action,account,amount,owner);
    }

    public Map<String,Object> proposeFreeze(int owner,String requestId,String account,boolean frozen) {
        enabled();
        if(requestId==null || !requestId.matches("[a-zA-Z0-9:_-]{8,100}")) throw new IllegalArgumentException("请提供8至100位请求编号");
        if(account==null || !(account.matches("[1-9][0-9]{0,9}") || properties.getFeeRecipientUserId().equals(account))) throw new IllegalArgumentException("账户编号不合法");
        if(!properties.getFeeRecipientUserId().equals(account) && jdbc.queryForObject("select count(*) from `user` where id=?",Integer.class,account)==0) throw new IllegalArgumentException("用户不存在");
        String request=Hash.sha3String("freeze:"+owner+":"+requestId);
        return tx.execute(status->{
            jdbc.queryForList("select id from `user` where id=? for update",owner);
            List<String> previous=jdbc.queryForList("select id from governance_proposal where request_key=?",String.class,request);
            if(!previous.isEmpty()) {
                Proposal old=load(previous.get(0));
                if(!old.account.equals(account) || old.amount!=(frozen?1:0)) throw new IllegalArgumentException("同一请求不能修改冻结参数");
                return view(old);
            }
            String id=UUID.randomUUID().toString();create(id,request,null,2,account,frozen?1:0,owner);return view(load(id));
        });
    }

    private void create(String id,String request,String settlement,int action,String account,long amount,int owner) {
        enabled();long lifetime=properties.getProposalLifetimeSeconds();
        if(lifetime<60 || lifetime>604800) throw new IllegalStateException("提案有效期须在60秒至7天之间");
        long deadline=Math.multiplyExact(Instant.now().getEpochSecond()+lifetime,properties.isFisco()?1000:1);
        if(action==1 || action==3) {
            long day=jdbc.queryForObject("select day_index from mint_quota_admission where settlement_id=?",Long.class,settlement);
            long endOfDaySeconds=Math.subtractExact(Math.multiplyExact(day+1,86400),8*3600+1);
            deadline=Math.min(deadline,Math.multiplyExact(endOfDaySeconds,properties.isFisco()?1000:1));
        }
        Proposal p=new Proposal(id,settlement,action,account,amount,deadline,"",properties.getContractAddress(),"MULTISIG",null);
        String digest=digest(p);
        jdbc.update("insert into governance_proposal(id,request_key,settlement_id,action,account_id,amount,deadline,digest,contract_address,state,created_by) values(?,?,?,?,?,?,?,?,?,'MULTISIG',?)",
                id,request,settlement,action,account,amount,deadline,digest,p.contract,owner);
    }

    public Map<String,Object> approve(String id,String signature,int admin) {
        enabled();
        return tx.execute(status->{
            jdbc.queryForList("select id from governance_proposal where id=? for update",id);
            Proposal p=load(id);
            if(!"MULTISIG".equals(p.state)) throw new IllegalStateException("提案不再接受审批");
            if(p.deadline<(properties.isFisco()?Instant.now().toEpochMilli():Instant.now().getEpochSecond())) throw new IllegalStateException("提案已过期");
            verifyDomain(p);
            String approver=recover(p.digest,signature);
            boolean allowed=(Boolean)call(new Function("isApprover",List.of(new Address(approver)),List.of(new TypeReference<Bool>(){}))).get(0).getValue();
            if(!allowed) throw new IllegalArgumentException("签名人不是该合约的审批人");
            List<String> existing=jdbc.queryForList("select signature from governance_approval where proposal_id=? and approver=?",String.class,id,approver);
            if(existing.isEmpty()) jdbc.update("insert into governance_approval(proposal_id,approver,signature,submitted_by) values(?,?,?,?)",id,approver,signature.toLowerCase(Locale.ROOT),admin);
            int count=jdbc.queryForObject("select count(*) from governance_approval where proposal_id=?",Integer.class,id);
            int threshold=((BigInteger)call(new Function("threshold",List.of(),List.of(new TypeReference<Uint256>(){}))).get(0).getValue()).intValueExact();
            if(threshold<2 || threshold>10) throw new IllegalStateException("合约审批阈值不合法");
            if(count>=threshold) {
                jdbc.update("update governance_proposal set state='READY' where id=?",id);
                if(p.settlement!=null) {
                    int changed=jdbc.update("update settlement_order set state='PENDING',updated_at=CURRENT_TIMESTAMP where "+routing.at("order",p.settlement)+" and id=? and state='MULTISIG'",p.settlement);
                    if(changed!=1) throw new IllegalStateException("结算审批状态不匹配");
                    jdbc.update("insert into settlement_outbox(order_id,created_at) select id,created_at from settlement_order where "+routing.at("order",p.settlement)+" and id=?",p.settlement);
                }
            }
            return view(load(id));
        });
    }

    public String mintCalldata(String settlement,String account,BigInteger amount) {
        Proposal p=load(settlement);
        if((p.action!=1 && p.action!=3) || !settlement.equals(p.settlement) || !account.equals(p.account) || !amount.equals(BigInteger.valueOf(p.amount))) throw new IllegalStateException("提案与发币结算不一致");
        return calldata(p);
    }

    public Map<String,Object> executeFreeze(String id) throws Exception {
        enabled();Proposal p=load(id);
        if(p.action!=2 || p.settlement!=null) throw new IllegalArgumentException("发币由结算消费者执行");
        if("EXECUTED".equals(p.state)) return view(p);
        String hash=transactions.getObject().send(calldata(p),"GOVERNANCE:"+id);
        jdbc.update("update governance_proposal set state='EXECUTED',tx_hash=? where id=? and state='READY'",hash,id);
        return view(load(id));
    }

    public void completeMint(String settlement,String hash) {
        jdbc.update("update governance_proposal set state='EXECUTED',tx_hash=? where settlement_id=? and state='READY'",hash,settlement);
    }

    private String calldata(Proposal p) {
        enabled();if(!"READY".equals(p.state) && !"EXECUTED".equals(p.state)) throw new IllegalStateException("提案审批未完成");
        // 不在这里按本机时间拒绝：已广播的过期提案仍须查询原交易结果。
        verifyDomain(p);
        List<DynamicBytes> signatures=new ArrayList<>();
        for(String value:jdbc.queryForList("select signature from governance_approval where proposal_id=? order by approver",String.class,p.id)) signatures.add(new DynamicBytes(Numeric.hexStringToByteArray(value)));
        List<Type> args=args(p);args.add(new DynamicArray<>(DynamicBytes.class,signatures));
        return FunctionEncoder.encode(new Function("execute",args,List.of()));
    }

    private void verifyDomain(Proposal p) {
        if(!properties.getContractAddress().equalsIgnoreCase(p.contract) || !p.digest.equals(digest(p))) throw new IllegalStateException("提案的链或合约域已变化，请恢复原配置");
        if(p.action==1 || p.action==3) {
            var quota=jdbc.queryForMap("select * from mint_quota_admission where settlement_id=?",p.settlement);
            String policy=Numeric.toHexString((byte[])call(new Function("issuancePolicyHash",List.of(),List.of(new TypeReference<Bytes32>(){}))).get(0).getValue());
            if(!policy.equals(quota.get("policy_hash")))throw new IllegalStateException("提案发行政策已变化");
            boolean reviewed=((Number)quota.get("lifetime_review")).intValue()==1;
            if(reviewed!=(p.action==3))throw new IllegalStateException("累计复核标记与签名动作不一致");
        }
    }
    private String digest(Proposal p) {return Numeric.toHexString((byte[])call(new Function("proposalDigest",args(p),List.of(new TypeReference<Bytes32>(){}))).get(0).getValue());}
    private List<Type> args(Proposal p) {return new ArrayList<>(List.of(new Bytes32(Numeric.hexStringToByteArray(Hash.sha3String(p.id))),new Uint8(p.action),new Utf8String(p.account),new Uint256(p.amount),new Uint256(p.deadline)));}
    private List<Type> call(Function function) {try{return chain.getObject().callView(function);}catch(Exception e){throw new IllegalStateException("治理合约查询失败",e);}}
    private void enabled() {if(!properties.isGovernanceEnabled()) throw new IllegalStateException("未启用多签治理合约");}

    public static String recover(String digest,String signature) {
        if(signature==null || !signature.matches("0x[0-9a-fA-F]{130}")) throw new IllegalArgumentException("需要65字节personal_sign签名");
        byte[] bytes=Numeric.hexStringToByteArray(signature);
        BigInteger s=new BigInteger(1,Arrays.copyOfRange(bytes,32,64));
        if((bytes[64]!=27 && bytes[64]!=28) || s.signum()==0 || s.compareTo(Sign.CURVE_PARAMS.getN().shiftRight(1))>0) throw new IllegalArgumentException("签名不规范");
        try {return "0x"+Keys.getAddress(Sign.signedPrefixedMessageToKey(Numeric.hexStringToByteArray(digest),new Sign.SignatureData(bytes[64],Arrays.copyOfRange(bytes,0,32),Arrays.copyOfRange(bytes,32,64))));}
        catch(Exception e) {throw new IllegalArgumentException("签名无效",e);}
    }

    public List<Map<String,Object>> list() {enabled();List<Map<String,Object>> result=new ArrayList<>();for(String id:jdbc.queryForList("select id from governance_proposal order by created_at desc limit 100",String.class)) result.add(view(load(id)));return result;}
    private Proposal load(String id) {
        List<Proposal> rows=jdbc.query("select * from governance_proposal where id=?",(rs,n)->new Proposal(rs.getString("id"),rs.getString("settlement_id"),rs.getInt("action"),rs.getString("account_id"),rs.getLong("amount"),rs.getLong("deadline"),rs.getString("digest"),rs.getString("contract_address"),rs.getString("state"),rs.getString("tx_hash")),id);
        if(rows.isEmpty()) throw new IllegalArgumentException("提案不存在");return rows.get(0);
    }
    private Map<String,Object> view(Proposal p) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("id",p.id);out.put("settlementId",p.settlement);out.put("action",p.action==1?"MINT":p.action==3?"MINT_LIFETIME_REVIEW":"FREEZE");out.put("account",p.account);out.put("value",Long.toString(p.amount));out.put("deadline",p.deadline);out.put("digest",p.digest);out.put("contract",p.contract);out.put("state",p.state);out.put("txHash",p.hash);
        if(p.action==1 || p.action==3) {
            var quota=jdbc.queryForMap("select day_index,max_supply,lifetime_threshold,global_daily_limit,account_daily_limit,lifetime_review,policy_hash from mint_quota_admission where settlement_id=?",p.settlement);
            Map<String,Object> readable=new LinkedHashMap<>();quota.forEach((key,value)->readable.put(key,String.valueOf(value)));out.put("issuancePolicy",readable);
        }
        out.put("approvers",jdbc.queryForList("select approver from governance_approval where proposal_id=? order by approver",String.class,p.id));return out;
    }
    private static final class Proposal {
        final String id,settlement,account,digest,contract,state,hash;final int action;final long amount,deadline;
        Proposal(String id,String settlement,int action,String account,long amount,long deadline,String digest,String contract,String state,String hash){this.id=id;this.settlement=settlement;this.action=action;this.account=account;this.amount=amount;this.deadline=deadline;this.digest=digest;this.contract=contract;this.state=state;this.hash=hash;}
    }
}
