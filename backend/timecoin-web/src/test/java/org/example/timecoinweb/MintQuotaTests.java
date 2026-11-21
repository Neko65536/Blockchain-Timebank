package org.example.timecoinweb;

import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.service.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MintQuotaTests {
    BlockchainProperties props;TimeCoinChainService chain;MintQuotaService quota;JdbcTemplate jdbc;TransactionTemplate tx;
    AtomicLong day=new AtomicLong(20000);
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:quota"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema-chain-submission.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        props=new BlockchainProperties();IssuanceTestSupport.configure(props);props.setGovernanceEnabled(true);props.setContractAddress("0x"+"12".repeat(20));
        chain=mock(TimeCoinChainService.class);
        when(chain.callView(any())).thenAnswer(call->IssuanceTestSupport.view(call.getArgument(0),props,day.get()));
        quota=new MintQuotaService(jdbc,props,chain);setDay(day.get());
    }
    void setDay(long next){day.set(next);ReflectionTestUtils.setField(quota,"clock",Clock.fixed(Instant.ofEpochSecond(next*86400-28800),ZoneOffset.UTC));}
    void reserve(String account,long amount,boolean reviewed){tx.executeWithoutResult(s->quota.reserve(UUID.randomUUID().toString(),account,BigInteger.valueOf(amount),reviewed));}
    @Test void enabledChainRequiresAllFourExplicitLimitsAndValidRelationships(){
        var p=new BlockchainProperties();p.validateIssuanceConfiguration();p.setEnabled(true);
        assertThrows(IllegalStateException.class,p::validateIssuanceConfiguration);
        IssuanceTestSupport.configure(p);p.validateIssuanceConfiguration();
        for(String field:List.of("maxSupply","lifetimeMintThreshold","globalDailyMintLimit","accountDailyMintLimit")){
            Object saved=ReflectionTestUtils.getField(p,field);ReflectionTestUtils.setField(p,field,null);
            assertThrows(IllegalStateException.class,p::validateIssuanceConfiguration);ReflectionTestUtils.setField(p,field,saved);
        }
        p.setAccountDailyMintLimit(p.getGlobalDailyMintLimit().add(BigInteger.ONE));
        assertThrows(IllegalStateException.class,p::validateIssuanceConfiguration);
    }
    @Test void concurrentAdministratorsCannotExceedGlobalOrAccountAdmissions() throws Exception {
        props.setGlobalDailyMintLimit(BigInteger.valueOf(1000));props.setAccountDailyMintLimit(BigInteger.valueOf(300));
        var start=new CountDownLatch(1);AtomicInteger accepted=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(10)){
            List<Future<?>> work=new ArrayList<>();
            for(int i=0;i<20;i++){String account=String.valueOf(i%4+1);work.add(pool.submit(()->{
                try {start.await();reserve(account,100,false);accepted.incrementAndGet();}
                catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("受理额度不足"));}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new RuntimeException(interrupted);}
            }));}
            start.countDown();for(var task:work)task.get(15,TimeUnit.SECONDS);
        }
        assertEquals(10,accepted.get());assertEquals(1000,jdbc.queryForObject("select sum(amount) from mint_quota_admission",Integer.class));
        assertTrue(jdbc.queryForList("select sum(amount) from mint_quota_admission group by account_id",Integer.class).stream().allMatch(n->n<=300));
    }
    @Test void rollbackReleasesAdmissionAndNextBeijingDayHasIndependentBudget(){
        props.setAccountDailyMintLimit(BigInteger.valueOf(10));
        assertThrows(IllegalArgumentException.class,()->tx.executeWithoutResult(s->{quota.reserve("rolled-back","1",BigInteger.TEN,false);throw new IllegalArgumentException("rollback");}));
        assertEquals(0,jdbc.queryForObject("select count(*) from mint_quota_admission",Integer.class));
        reserve("1",10,false);assertThrows(IllegalStateException.class,()->reserve("1",1,false));
        setDay(day.get()+1);reserve("1",10,false);
        assertEquals(2,jdbc.queryForObject("select count(distinct day_index) from mint_quota_admission",Integer.class));
    }
    @Test void oldContractPolicyMismatchClockMismatchAndOversupplyFailClosed() throws Exception {
        doThrow(new IllegalStateException("old ABI")).when(chain).callView(any());
        assertThrows(IllegalStateException.class,()->reserve("1",1,false));
        doAnswer(call->{Function f=call.getArgument(0);return f.getName().equals("MAX_SUPPLY")?List.of(new Uint256(42)):IssuanceTestSupport.view(f,props,day.get());}).when(chain).callView(any());
        assertThrows(IllegalStateException.class,()->reserve("1",1,false));
        doAnswer(call->IssuanceTestSupport.view(call.getArgument(0),props,day.get()+1)).when(chain).callView(any());
        assertThrows(IllegalStateException.class,()->reserve("1",1,false));
        doAnswer(call->{Function f=call.getArgument(0);return f.getName().equals("totalSupply")?List.of(new Uint256(props.getMaxSupply())):IssuanceTestSupport.view(f,props,day.get());}).when(chain).callView(any());
        assertThrows(IllegalStateException.class,()->reserve("1",1,true));
        assertEquals(0,jdbc.queryForObject("select count(*) from mint_quota_admission",Integer.class));
    }
    @Test void lifetimeWarningNeedsExplicitGovernedReviewAndCannotBypassDailyLimit() throws Exception {
        doAnswer(call->{Function f=call.getArgument(0);return f.getName().equals("lifetimeMinted")?List.of(new Uint256(props.getLifetimeMintThreshold())):IssuanceTestSupport.view(f,props,day.get());}).when(chain).callView(any());
        assertThrows(IllegalStateException.class,()->reserve("1",1,false));reserve("1",1,true);
        assertEquals(1,jdbc.queryForObject("select lifetime_review from mint_quota_admission",Integer.class));
        assertThrows(IllegalStateException.class,()->reserve("1",1440,true));
        props.setGovernanceEnabled(false);assertThrows(IllegalArgumentException.class,()->reserve("2",1,true));
    }
}
