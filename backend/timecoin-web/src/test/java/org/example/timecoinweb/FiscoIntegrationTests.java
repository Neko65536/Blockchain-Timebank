package org.example.timecoinweb;

import tools.jackson.databind.ObjectMapper;
import org.example.timecoinweb.config.*;
import org.example.timecoinweb.service.FiscoTransactionService;
import org.example.timecoinweb.signer.RemoteSigner;
import org.fisco.bcos.sdk.v3.BcosSDK;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.crypto.CryptoSuite;
import org.fisco.bcos.sdk.jni.utilities.tx.TransactionBuilderJniObj;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.web3j.abi.*;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.*;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 显式开启；只允许127.0.0.1测试链，生成临时密钥、部署独立合约，真实启动signer子进程。 */
@EnabledIfSystemProperty(named="fisco.integration",matches="true")
class FiscoIntegrationTests {
    @Test
    @EnabledIfSystemProperty(named="test.fisco-existing-contract",matches="0x[0-9a-fA-F]{40}")
    void existingContractSurvivesNodeRestarts() throws Exception {
        BcosSDK sdk=BcosSDK.build(System.getProperty("test.fisco-config"));
        try {
            Client client=sdk.getClient("group0");
            Function function=new Function("currentTime",List.of(),List.of(new TypeReference<Uint256>(){}));
            var result=client.call(new org.fisco.bcos.sdk.v3.client.protocol.request.Transaction(System.getProperty("test.fisco-operator"),System.getProperty("test.fisco-existing-contract"),Numeric.hexStringToByteArray(FunctionEncoder.encode(function)))).getCallResult();
            assertEquals(0,result.getStatus());assertFalse(FunctionReturnDecoder.decode(result.getOutput(),function.getOutputParameters()).isEmpty());
        } finally {sdk.stopAll();}
    }
    @Test void realChainAndSeparateSignerPreserveReceiptAcrossLostResponse() throws Exception {
        Path config=Path.of(System.getProperty("test.fisco-config"));
        String configText=Files.readString(config);
        assertTrue(configText.contains("127.0.0.1:20250"));
        BcosSDK sdk=BcosSDK.build(config.toString());Process process=null;
        try {
            Client client=sdk.getClient("group0");ObjectMapper json=new ObjectMapper();
            var operator=new CryptoSuite(0).getCryptoKeyPair();
            Credentials a=Credentials.create(Keys.createEcKeyPair()),b=Credentials.create(Keys.createEcKeyPair());
            String binary=json.readTree(Files.readString(Path.of("../../TimeCoinSystem/artifacts/contracts/GovernedTimeCoin.sol/GovernedTimeCoin.json"))).path("bytecode").asText();
            String constructor=FunctionEncoder.encodeConstructor(List.of(new Address(operator.getAddress()),new Bytes32(Numeric.hexStringToByteArray(Hash.sha3String("integration:"+UUID.randomUUID()))),new DynamicArray<>(Address.class,List.of(new Address(a.getAddress()),new Address(b.getAddress()))),new Uint256(2),new StaticArray5<>(Uint256.class,List.of(new Uint256(20000000),new Uint256(100000),new Uint256(100000),new Uint256(1440),new Uint256(1000)))));
            var deployment=TransactionBuilderJniObj.createSignedTransaction(operator.getJniKeyPair(),client.getGroup(),client.getChainId(),"",Numeric.cleanHexPrefix(binary)+constructor,"",client.getBlockLimit().longValueExact(),0);
            var receipt=client.sendTransaction(deployment.getSignedTx(),false).getTransactionReceipt();
            assertNotNull(receipt);assertEquals(0,receipt.getStatus());
            String contract=receipt.getContractAddress();if(!contract.startsWith("0x")) contract="0x"+contract;
            Function clock=new Function("currentTime",List.of(),List.of(new TypeReference<Uint256>(){}));
            var clockOutput=client.call(new org.fisco.bcos.sdk.v3.client.protocol.request.Transaction(operator.getAddress(),contract,Numeric.hexStringToByteArray(FunctionEncoder.encode(clock)))).getCallResult();
            long chainTime=((BigInteger)FunctionReturnDecoder.decode(clockOutput.getOutput(),clock.getOutputParameters()).get(0).getValue()).longValueExact();
            assertTrue(Math.abs(System.currentTimeMillis()-chainTime)<60000,"必须核验FISCO合约时间单位为毫秒");
            String observerFile=System.getProperty("test.fisco-observer-file");
            if(observerFile!=null) {
                String node=Files.readString(Path.of(observerFile)).trim();
                if(!client.getObserverList().getObserverList().contains(node)) {
                    var admin=new CryptoSuite(0).getKeyPairFactory().createKeyPair(Files.readString(Path.of(System.getProperty("test.fisco-admin-keyfile"))).trim());
                    var governance=new org.fisco.bcos.sdk.v3.contract.auth.manager.AuthManager(client,admin);
                    BigInteger proposalId=governance.createSetConsensusWeightProposal(node,BigInteger.ZERO,true);
                    System.out.println("FISCO_MEMBERSHIP_PROPOSAL id="+proposalId+" status="+governance.getProposalInfo(proposalId).getStatusString());
                }
                assertEquals(3,client.getSealerList().getSealerList().size());
                assertEquals(1,client.getObserverList().getObserverList().size());
            }
            long day=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toEpochDay();
            long deadline=Math.min(System.currentTimeMillis()+600000,((day+1)*86400-28801)*1000);
            List<Type> proposal=new ArrayList<>(List.of(new Bytes32(Numeric.hexStringToByteArray(Hash.sha3String("mint-fixture"))),new Uint8(1),new Utf8String("1"),new Uint256(100),new Uint256(deadline)));
            Function digestFunction=new Function("proposalDigest",proposal,List.of(new TypeReference<Bytes32>(){}));
            var call=client.call(new org.fisco.bcos.sdk.v3.client.protocol.request.Transaction(operator.getAddress(),contract,Numeric.hexStringToByteArray(FunctionEncoder.encode(digestFunction)))).getCallResult();
            assertEquals(0,call.getStatus());
            byte[] digest=(byte[])FunctionReturnDecoder.decode(call.getOutput(),digestFunction.getOutputParameters()).get(0).getValue();
            List<Credentials> keys=new ArrayList<>(List.of(a,b));keys.sort(Comparator.comparing(Credentials::getAddress));
            List<DynamicBytes> signatures=new ArrayList<>();
            for(Credentials key:keys) {var sig=Sign.signPrefixedMessage(digest,key.getEcKeyPair());signatures.add(new DynamicBytes(Numeric.hexStringToByteArray(Numeric.toHexString(sig.getR())+Numeric.toHexStringNoPrefix(sig.getS())+Numeric.toHexStringNoPrefix(sig.getV()))));}
            proposal.add(new DynamicArray<>(DynamicBytes.class,signatures));
            String mint=FunctionEncoder.encode(new Function("execute",proposal,List.of()));
            String transfer=FunctionEncoder.encode(new Function("transfer",List.of(new Utf8String("1"),new Utf8String("2"),new Uint256(9)),List.of()));
            Path local=Files.createTempDirectory(Path.of("target"),"fisco-signer-");
            Path keyFile=local.resolve("operator.key");Files.writeString(keyFile,operator.getHexPrivateKey());
            int port;try(java.net.ServerSocket socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) {port=socket.getLocalPort();}
            String token=UUID.randomUUID().toString()+UUID.randomUUID();
            // Windows命令长度有限，通过manifest引用完整测试类路径。
            java.util.jar.Manifest manifest=new java.util.jar.Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");
            String classpath=Arrays.stream(System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                    .map(path->Path.of(path).toUri().toASCIIString()).collect(java.util.stream.Collectors.joining(" "));
            manifest.getMainAttributes().putValue("Class-Path",classpath);Path launcher=local.resolve("classpath.jar");
            try(java.util.jar.JarOutputStream output=new java.util.jar.JarOutputStream(Files.newOutputStream(launcher),manifest)) { }
            ProcessBuilder builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",launcher.toAbsolutePath().toString(),"org.example.timecoinweb.signer.SignerMain");
            builder.environment().putAll(Map.of("SIGNER_KEY_FILE",keyFile.toAbsolutePath().toString(),"SIGNER_TOKEN",token,"SIGNER_CHAIN_ID",client.getChainId(),"SIGNER_GROUP_ID",client.getGroup(),"SIGNER_CONTRACT",contract,"SIGNER_SELECTORS",mint.substring(2,10)+","+transfer.substring(2,10),"SIGNER_PORT",Integer.toString(port)));
            builder.redirectErrorStream(true).redirectOutput(local.resolve("signer.log").toFile());process=builder.start();
            boolean started=false;
            for(int i=0;i<40 && process.isAlive();i++) {
                try(java.net.Socket socket=new java.net.Socket("127.0.0.1",port)) {started=true;break;}catch(java.io.IOException waiting){Thread.sleep(250);}
            }
            assertTrue(started,"独立签名进程未启动，检查target/fisco-signer-*日志");
            DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:fisco"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
            new ResourceDatabasePopulator(new ClassPathResource("schema-chain-submission.sql"),new ClassPathResource("schema-settlement.sql")).execute(ds);
            JdbcTemplate jdbc=new JdbcTemplate(ds);BlockchainProperties props=new BlockchainProperties();props.setBackend("fisco");props.setContractAddress(contract);props.setSignerAddress(operator.getAddress());props.setReceiptAttempts(3);props.setReceiptPollIntervalMs(100);
            RemoteSigner remote=new RemoteSigner("http://127.0.0.1:"+port+"/v1/sign",token);
            FiscoTransactionService service=new FiscoTransactionService(jdbc,new DataSourceTransactionManager(ds),new ShardRouting(jdbc),props,client,remote,json);
            String mintHash=service.send(mint,"test-mint");assertEquals(mintHash,service.send(mint,"test-mint"));
            Client unreliable=mock(Client.class,org.mockito.AdditionalAnswers.delegatesTo(client));
            doAnswer(invocation->{client.sendTransaction(invocation.getArgument(0),false);throw new IllegalStateException("simulate lost receipt response");}).when(unreliable).sendTransaction(anyString(),eq(false));
            FiscoTransactionService interrupted=new FiscoTransactionService(jdbc,new DataSourceTransactionManager(ds),new ShardRouting(jdbc),props,unreliable,remote,json);
            assertThrows(IllegalStateException.class,()->interrupted.send(transfer,"test-transfer"));
            String key=Hash.sha3String("business:test-transfer");
            String hash=jdbc.queryForObject("select tx_hash from chain_submission where request_key=?",String.class,key);
            assertEquals("PREPARED",jdbc.queryForObject("select state from chain_submission where request_key=?",String.class,key));
            assertEquals(hash,service.send(transfer,"test-transfer"));assertEquals(hash,service.resume(hash));
            assertEquals(2,jdbc.queryForObject("select count(*) from chain_submission",Integer.class));
            assertEquals(0,jdbc.queryForObject("select count(*) from chain_submission where nonce_value is not null",Integer.class));
            assertThrows(IllegalStateException.class,()->service.send(mint,"test-transfer"));
            Function balance=new Function("balanceOf",List.of(new Utf8String("2")),List.of(new TypeReference<Uint256>(){}));
            var output=client.call(new org.fisco.bcos.sdk.v3.client.protocol.request.Transaction(operator.getAddress(),contract,Numeric.hexStringToByteArray(FunctionEncoder.encode(balance)))).getCallResult();
            assertEquals(BigInteger.valueOf(9),FunctionReturnDecoder.decode(output.getOutput(),balance.getOutputParameters()).get(0).getValue());
            for(Function counter:List.of(
                    new Function("totalSupply",List.of(),List.of(new TypeReference<Uint256>(){})),
                    new Function("lifetimeMinted",List.of(new Utf8String("1")),List.of(new TypeReference<Uint256>(){})),
                    new Function("globalDailyMinted",List.of(new Uint256(day)),List.of(new TypeReference<Uint256>(){})),
                    new Function("accountDailyMinted",List.of(new Utf8String("1"),new Uint256(day)),List.of(new TypeReference<Uint256>(){})))) {
                var actual=client.call(new org.fisco.bcos.sdk.v3.client.protocol.request.Transaction(operator.getAddress(),contract,Numeric.hexStringToByteArray(FunctionEncoder.encode(counter)))).getCallResult();
                assertEquals(0,actual.getStatus());
                assertEquals(BigInteger.valueOf(100),FunctionReturnDecoder.decode(actual.getOutput(),counter.getOutputParameters()).get(0).getValue());
            }
            System.out.println("FISCO_EVIDENCE chain="+client.getChainId()+" group="+client.getGroup()+" contract="+contract+" mint="+mintHash+" transfer="+hash+" submissions=2 receiverBalance=9 separateSigner=true");
        } finally {if(process!=null){process.destroy();if(!process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS))process.destroyForcibly();}sdk.stopAll();}
    }
}
