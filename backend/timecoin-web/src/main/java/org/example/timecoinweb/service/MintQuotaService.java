package org.example.timecoinweb.service;

import org.example.timecoinweb.config.BlockchainProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.*;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.time.*;
import java.util.*;

/** 仅作受理配额和友好拒绝；链上独立硬限制是最终防线。 */
@Service
public class MintQuotaService {
    private final JdbcTemplate jdbc;
    private final BlockchainProperties properties;
    private final TimeCoinChainService chain;
    private Clock clock=Clock.systemUTC();
    public MintQuotaService(JdbcTemplate jdbc,BlockchainProperties properties,TimeCoinChainService chain){
        this.jdbc=jdbc;this.properties=properties;this.chain=chain;
    }
    public void reserve(String settlement,String account,BigInteger amount,boolean reviewed) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("发行配额必须与结算同事务登记");
        properties.requireIssuanceLimits();
        if(reviewed && !properties.isGovernanceEnabled())throw new IllegalArgumentException("累计超阈值只能通过多签人工复核");
        String scope=Hash.sha3String(properties.getBackend()+":"+properties.getExpectedChainId()+":"+properties.getGroupId()+":"+properties.getRpcUrl()+":"+properties.getContractAddress().toLowerCase(Locale.ROOT));
        long day=LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).toEpochDay();
        verifyPolicy();
        if(!number("currentDayIndex").equals(BigInteger.valueOf(day)))throw new IllegalStateException("链上与服务端北京时间日窗口不一致");
        requireCapacity(number("totalSupply"),amount,properties.getMaxSupply(),"总发行额度不足");
        requireCapacity(number("globalDailyMinted",new Uint256(day)),amount,properties.getGlobalDailyMintLimit(),"链上全局当日额度不足");
        requireCapacity(number("accountDailyMinted",new Utf8String(account),new Uint256(day)),amount,properties.getAccountDailyMintLimit(),"链上账户当日额度不足");
        if(!reviewed && number("lifetimeMinted",new Utf8String(account)).add(amount).compareTo(properties.getLifetimeMintThreshold())>0)
            throw new IllegalStateException("累计铸币超过复核阈值，请明确申请超阈值多签复核");
        jdbc.update("insert into mint_quota_lock(scope_key) values(?) on duplicate key update scope_key=values(scope_key)",scope);
        jdbc.queryForObject("select scope_key from mint_quota_lock where scope_key=? for update",String.class,scope);
        BigInteger global=BigInteger.valueOf(jdbc.queryForObject("select coalesce(sum(amount),0) from mint_quota_admission where scope_key=? and day_index=?",Long.class,scope,day));
        BigInteger personal=BigInteger.valueOf(jdbc.queryForObject("select coalesce(sum(amount),0) from mint_quota_admission where scope_key=? and day_index=? and account_id=?",Long.class,scope,day,account));
        requireCapacity(global,amount,properties.getGlobalDailyMintLimit(),"服务端全局当日受理额度不足");
        requireCapacity(personal,amount,properties.getAccountDailyMintLimit(),"服务端账户当日受理额度不足");
        String policyHash=Numeric.toHexString((byte[])call("issuancePolicyHash",List.of(),new TypeReference<Bytes32>(){}).getValue());
        jdbc.update("insert into mint_quota_admission(settlement_id,scope_key,account_id,day_index,amount,lifetime_review,policy_hash,max_supply,lifetime_threshold,global_daily_limit,account_daily_limit) values(?,?,?,?,?,?,?,?,?,?,?)",
                settlement,scope,account,day,amount.longValueExact(),reviewed?1:0,policyHash,properties.getMaxSupply().longValueExact(),properties.getLifetimeMintThreshold().longValueExact(),properties.getGlobalDailyMintLimit().longValueExact(),properties.getAccountDailyMintLimit().longValueExact());
    }
    public void verifyPolicy() {
        properties.requireIssuanceLimits();
        if(!number("MAX_SUPPLY").equals(properties.getMaxSupply())
                || !number("lifetimeMintThreshold").equals(properties.getLifetimeMintThreshold())
                || !number("globalDailyLimit").equals(properties.getGlobalDailyMintLimit())
                || !number("accountDailyLimit").equals(properties.getAccountDailyMintLimit())
                || !number("timestampDivisor").equals(BigInteger.valueOf(properties.isFisco()?1000:1)))
            throw new IllegalStateException("链上发行政策与服务端配置不一致，拒绝发币");
    }
    private void requireCapacity(BigInteger used,BigInteger amount,BigInteger limit,String message){
        if(amount==null || amount.signum()<=0 || used.add(amount).compareTo(limit)>0)throw new IllegalStateException(message);
    }
    private BigInteger number(String name,Type... args){return (BigInteger)call(name,Arrays.asList(args),new TypeReference<Uint256>(){}).getValue();}
    private Type call(String name,List<Type> args,TypeReference<?> output){
        try {var values=chain.callView(new Function(name,args,List.of(output)));if(values.size()!=1)throw new IllegalStateException();return values.get(0);}
        catch(Exception failure){throw new IllegalStateException("发行政策查询失败：请确认已部署并配置带额度约束的新合约",failure);}
    }
}
