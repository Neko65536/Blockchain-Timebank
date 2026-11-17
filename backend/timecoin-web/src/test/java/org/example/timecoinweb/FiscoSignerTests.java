package org.example.timecoinweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.timecoinweb.signer.*;
import org.fisco.bcos.sdk.jni.utilities.tx.TransactionStructBuilderJniObj;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class FiscoSignerTests {
    private final ObjectMapper json=new ObjectMapper();
    private final String contract="0x"+"12".repeat(20);
    private ObjectNode request() {return json.createObjectNode().put("chainId","chain0").put("groupId","group0").put("to",contract).put("input","0x12345678").put("blockLimit",500);}
    @Test void createsRealFiscoSignedTransactionWithRandomNonce() throws Exception {
        SignerEngine signer=new SignerEngine("1","chain0","group0",contract,Set.of("12345678"));
        var one=signer.sign(request());var two=signer.sign(request());
        var decoded=TransactionStructBuilderJniObj.decodeTransactionStruct((String)one.get("rawTx"));
        assertEquals("chain0",decoded.getTransactionData().getChainId());assertEquals(500,decoded.getTransactionData().getBlockLimit());
        assertEquals(one.get("txHash"),decoded.getTransactionData().calculateHash(0));
        assertNotEquals(one.get("txHash"),two.get("txHash"));
        assertTrue(decoded.getSignature().length>0);
    }
    @Test void rejectsWrongChainGroupTargetSelectorAndMalformedPayload() {
        SignerEngine signer=new SignerEngine("1","chain0","group0",contract,Set.of("12345678"));
        assertThrows(IllegalArgumentException.class,()->signer.sign(request().put("chainId","other")));
        assertThrows(IllegalArgumentException.class,()->signer.sign(request().put("groupId","other")));
        assertThrows(IllegalArgumentException.class,()->signer.sign(request().put("to","0x"+"34".repeat(20))));
        assertThrows(IllegalArgumentException.class,()->signer.sign(request().put("input","deadbeef")));
        assertThrows(IllegalArgumentException.class,()->signer.sign(request().put("input","123456789")));
        assertThrows(IllegalArgumentException.class,()->signer.sign(request().put("blockLimit",0)));
    }
    @Test void refusesInsecureRemoteTransportAndWeakTokens() {
        assertThrows(IllegalArgumentException.class,()->new RemoteSigner("http://example.com/v1/sign","x".repeat(32)));
        assertThrows(IllegalArgumentException.class,()->new RemoteSigner("http://127.0.0.1:8443/v1/sign","short"));
        assertThrows(IllegalArgumentException.class,()->new RemoteSigner("https://example.com/v1/sign?token=x","x".repeat(32)));
    }
}
