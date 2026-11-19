package org.example.timecoinweb;

import org.example.pojo.Activity;
import org.example.timecoinweb.config.SerializationConfig;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;
import java.time.*;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

class SerializationCompatibilityTests {
    @Test void activitySnapshotsAndRequestFingerprintsKeepJackson2Bytes() throws Exception {
        var legacy = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var current = new SerializationConfig().jsonMapper();
        Activity a = new Activity();
        a.setId(12); a.setTitle("陪同购物"); a.setQuota((short)2);
        a.setDate(LocalDate.of(2026,11,20)); a.setBegin(LocalTime.of(9,0));
        a.setDeadline(LocalDateTime.of(2026,11,19,18,30,0,123000000));
        var request = new LinkedHashMap<String,Object>();
        request.put("owner",12); request.put("amount",new BigInteger("9007199254740993"));
        request.put("activity",a); request.put("optional",null);
        String original=legacy.writeValueAsString(request), migrated=current.writeValueAsString(request);
        assertEquals(original,migrated);
        assertEquals(Hash.sha3String(original),Hash.sha3String(migrated));
        assertEquals(a,current.readValue(legacy.writeValueAsString(a),Activity.class));
    }
}
