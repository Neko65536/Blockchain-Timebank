package org.example.timecoinweb;

import org.example.timecoinweb.config.*;
import org.example.timecoinweb.service.*;
import org.example.timecoinweb.mapper.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.*;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GovernanceTests {
    JdbcTemplate jdbc;GovernanceService governance;SettlementService settlements;TimeCoinChainService chain;
    Credentials a=Credentials.create("2"),b=Credentials.create("3");
    String digest=Hash.sha3String("test-proposal");
    @BeforeEach void setup() {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:gov"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql"),new ClassPathResource("schema-chain-submission.sql"),new ClassPathResource("schema-settlement.sql")).execute(source);
        jdbc=new JdbcTemplate(source);jdbc.update("insert into `user`(id,username,password,role) values(8001,'gov-admin','test',3),(8002,'gov-target','test',1)");
        // N7 后后端默认 fisco；本测试以以太坊 mock 链验证秒级 deadline 语义，显式声明 backend。
        BlockchainProperties props=new BlockchainProperties();props.setBackend("ethereum");props.setGovernanceEnabled(true);props.setContractAddress("0x"+"12".repeat(20));
        IssuanceTestSupport.configure(props);
        chain=mock(TimeCoinChainService.class);when(chain.isChainReady()).thenReturn(true);
        try {when(chain.callView(any())).thenAnswer(call->{Function f=call.getArgument(0);
            if(f.getName().equals("proposalDigest")) return List.of(new Bytes32(Numeric.hexStringToByteArray(digest)));
            if(f.getName().equals("threshold")) return List.of(new Uint256(2));
            if(f.getName().equals("isApprover")) return List.of(new Bool(List.of(a.getAddress(),b.getAddress()).contains(f.getInputParameters().get(0).getValue())));
            return IssuanceTestSupport.view(f,props);
        });}catch(Exception impossible){throw new RuntimeException(impossible);}
        DefaultListableBeanFactory beans=new DefaultListableBeanFactory();beans.registerSingleton("chain",chain);
        ShardRouting routing=new ShardRouting(jdbc);DataSourceTransactionManager manager=new DataSourceTransactionManager(source);
        governance=new GovernanceService(jdbc,manager,props,routing,beans.getBeanProvider(TimeCoinChainService.class),beans.getBeanProvider(ChainTransactionService.class));
        beans.registerSingleton("governance",governance);
        settlements=new SettlementService(jdbc,manager,chain,mock(CoinBalanceLedgerService.class),mock(ActivityMapper.class),mock(VolMapper.class),props,new tools.jackson.databind.ObjectMapper(),routing,new MintQuotaService(jdbc,props,chain));
        ReflectionTestUtils.setField(settlements,"governance",beans.getBeanProvider(GovernanceService.class));
    }
    String signature(Credentials key) {
        Sign.SignatureData signed=Sign.signPrefixedMessage(Numeric.hexStringToByteArray(digest),key.getEcKeyPair());
        return Numeric.toHexString(signed.getR())+Numeric.toHexStringNoPrefix(signed.getS())+Numeric.toHexStringNoPrefix(signed.getV());
    }
    @Test void mintWaitsForTwoDifferentApproversBeforeCreatingOutbox() {
        Map<String,Object> order=settlements.submitTransfer(8001,"request-mint-001","ADMIN_MINT",null,"8002",BigInteger.TEN);
        String id=(String)order.get("settlementId");assertEquals("MULTISIG",order.get("state"));
        assertEquals(0,jdbc.queryForObject("select count(*) from settlement_outbox",Integer.class));
        assertThrows(IllegalStateException.class,()->governance.mintCalldata(id,"8002",BigInteger.TEN));
        governance.approve(id,signature(a),8001);governance.approve(id,signature(a),8001);
        assertEquals(1,jdbc.queryForObject("select count(*) from governance_approval",Integer.class));
        assertThrows(IllegalArgumentException.class,()->governance.approve(id,signature(Credentials.create("4")),8001));
        governance.approve(id,signature(b),8001);
        assertEquals("PENDING",jdbc.queryForObject("select state from settlement_order where id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("select count(*) from settlement_outbox",Integer.class));
        assertTrue(governance.mintCalldata(id,"8002",BigInteger.TEN).startsWith("0x"));
        assertThrows(IllegalStateException.class,()->governance.mintCalldata(id,"8002",BigInteger.ONE));
        assertEquals(id,settlements.submitTransfer(8001,"request-mint-001","ADMIN_MINT",null,"8002",BigInteger.TEN).get("settlementId"));
        assertEquals(1,jdbc.queryForObject("select count(*) from mint_quota_admission",Integer.class));
        assertTrue(governance.list().get(0).containsKey("issuancePolicy"));
        assertThrows(IllegalArgumentException.class,()->settlements.submitTransfer(8001,"request-mint-001","ADMIN_MINT",null,"8002",BigInteger.TEN,true));
    }
    @Test void expirationAndDomainChangesNeverReleaseSettlement() {
        String id=(String)settlements.submitTransfer(8001,"request-mint-002","ADMIN_MINT",null,"8002",BigInteger.TEN).get("settlementId");
        jdbc.update("update governance_proposal set deadline=1 where id=?",id);
        assertThrows(IllegalStateException.class,()->governance.approve(id,signature(a),8001));
        assertEquals(0,jdbc.queryForObject("select count(*) from settlement_outbox",Integer.class));
    }
    @Test void freezeUsesStableRequestAndRejectsMutatedParameters() {
        String id=(String)governance.proposeFreeze(8001,"freeze-001","8002",true).get("id");
        assertEquals(id,governance.proposeFreeze(8001,"freeze-001","8002",true).get("id"));
        assertThrows(IllegalArgumentException.class,()->governance.proposeFreeze(8001,"freeze-001","8002",false));
        assertThrows(IllegalArgumentException.class,()->GovernanceService.recover(digest,"not-a-signature"));
    }
    @Test void reviewedMintHasDistinctSignedActionAndQuotaDayBoundsDeadline(){
        String id=(String)settlements.submitTransfer(8001,"reviewed-mint-001","ADMIN_MINT",null,"8002",BigInteger.TEN,true).get("settlementId");
        assertEquals(3,jdbc.queryForObject("select action from governance_proposal where id=?",Integer.class,id));
        var proposal=governance.list().get(0);assertEquals("MINT_LIFETIME_REVIEW",proposal.get("action"));
        long day=jdbc.queryForObject("select day_index from mint_quota_admission where settlement_id=?",Long.class,id);
        assertEquals(day,(((Number)proposal.get("deadline")).longValue()+28800)/86400);
    }
}
