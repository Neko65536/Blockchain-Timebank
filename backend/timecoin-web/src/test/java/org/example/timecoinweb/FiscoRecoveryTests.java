package org.example.timecoinweb;

import tools.jackson.databind.ObjectMapper;
import org.example.timecoinweb.config.*;
import org.example.timecoinweb.service.FiscoTransactionService;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.model.TransactionReceipt;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.web3j.crypto.Hash;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FiscoRecoveryTests {
    JdbcTemplate jdbc;Client client;FiscoTransactionService service;BlockchainProperties properties;
    String hash=Hash.sha3String("saved-receipt");
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:recovery"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema-chain-submission.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);client=mock(Client.class,RETURNS_DEEP_STUBS);
        when(client.getChainId()).thenReturn("chain0");when(client.getGroup()).thenReturn("group0");
        when(client.getTransactionReceipt(hash,false).getTransactionReceipt()).thenReturn(null);
        when(client.getBlockNumber().getBlockNumber()).thenReturn(BigInteger.valueOf(500));
        properties=new BlockchainProperties();properties.setSignerAddress("0x"+"12".repeat(20));properties.setContractAddress("0x"+"34".repeat(20));properties.setReceiptAttempts(1);
        String scope=Hash.sha3String("fisco:chain0:group0:"+properties.getSignerAddress());
        jdbc.update("insert into chain_submission(request_key,scope_key,nonce_value,payload_hash,tx_hash,raw_tx,state) values(?,?,NULL,?,?,?,'PREPARED')",hash,scope,hash,hash,new ObjectMapper().writeValueAsString(Map.of("protocol","fisco","rawTx","abcd","blockLimit",100)));
        service=new FiscoTransactionService(jdbc,new DataSourceTransactionManager(ds),new ShardRouting(jdbc),properties,client,null,new ObjectMapper());
    }
    @Test void expiredUncertainTransactionNeverGetsReSignedOrBroadcast() {
        assertThrows(IllegalStateException.class,()->service.resume(hash));
        verify(client,never()).sendTransaction(anyString(),anyBoolean());
        assertEquals("PREPARED",jdbc.queryForObject("select state from chain_submission",String.class));
    }
    @Test void confirmedReceiptIsAcceptedEvenAfterBlockLimit() throws Exception {
        TransactionReceipt receipt=receipt(0);when(client.getTransactionReceipt(hash,false).getTransactionReceipt()).thenReturn(receipt);
        assertEquals(hash,service.resume(hash));assertEquals("CONFIRMED",jdbc.queryForObject("select state from chain_submission",String.class));
        verify(client,never()).sendTransaction(anyString(),anyBoolean());
    }
    @Test void wrongReceiptIsUncertainButActualRevertIsTerminal() {
        TransactionReceipt receipt=receipt(0);when(receipt.getTransactionHash()).thenReturn(Hash.sha3String("wrong"));
        when(client.getTransactionReceipt(hash,false).getTransactionReceipt()).thenReturn(receipt);
        assertThrows(IllegalStateException.class,()->service.resume(hash));assertEquals("PREPARED",jdbc.queryForObject("select state from chain_submission",String.class));
        TransactionReceipt reverted=receipt(1);
        when(client.getTransactionReceipt(hash,false).getTransactionReceipt()).thenReturn(reverted);
        assertThrows(IllegalStateException.class,()->service.resume(hash));assertEquals("FAILED",jdbc.queryForObject("select state from chain_submission",String.class));
    }
    private TransactionReceipt receipt(int status) {
        TransactionReceipt receipt=mock(TransactionReceipt.class);when(receipt.getTransactionHash()).thenReturn(hash);when(receipt.getBlockNumber()).thenReturn(BigInteger.ONE);
        when(receipt.getTo()).thenReturn(properties.getContractAddress());when(receipt.getFrom()).thenReturn(properties.getSignerAddress());when(receipt.getStatus()).thenReturn(status);return receipt;
    }
}
