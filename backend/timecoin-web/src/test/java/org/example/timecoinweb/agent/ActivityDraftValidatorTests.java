package org.example.timecoinweb.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.pojo.Activity;
import org.example.timecoinweb.config.BlockchainProperties;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class ActivityDraftValidatorTests {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final ActivityDraftValidator validator = new ActivityDraftValidator(json, new BlockchainProperties());
    @Test void rejectsModelAuthorityAndNumericCoercion() throws Exception {
        for (String payload : new String[]{"{\"oldId\":3}","{\"status\":2}","{\"quota\":1.5}","{\"quota\":65537}","{\"volunteerReward\":\"2\"}"})
            assertThrows(IllegalArgumentException.class, () -> validator.decode(json.readTree(payload)));
    }
    @Test void incompleteDraftReturnsActionableFeedbackWithoutGuessing() throws Exception {
        Activity draft = validator.decode(json.readTree("{\"title\":\"陪同买菜\"}"));
        var issues = validator.issues(draft, LocalDateTime.of(2026,11,19,9,0));
        assertTrue(issues.stream().anyMatch(s -> s.startsWith("address：")));
        assertTrue(issues.stream().anyMatch(s -> s.startsWith("date：")));
        assertNull(draft.getAddress());
        assertNull(draft.getDate());
        assertNull(draft.getVolunteerReward());
    }
    @Test void validatesChronologyAndServiceSpecificFields() throws Exception {
        Activity a = validator.decode(json.readTree("{\"title\":\"买菜\",\"description\":\"陪同购物\",\"quota\":1,\"phone\":\"123\",\"address\":\"社区一号门\",\"date\":\"2026-11-20\",\"begin\":\"09:00:00\",\"end\":\"10:00:00\",\"deadline\":\"2026-11-19T18:00:00\",\"volunteerReward\":0,\"serviceType\":\"other_service\",\"extraJson\":\"{\\\"customCategory\\\":\\\"购物\\\",\\\"serviceDetails\\\":\\\"陪同\\\"}\"}"));
        assertTrue(validator.issues(a, LocalDateTime.of(2026,11,19,9,0)).isEmpty());
        a.setEnd(a.getBegin());
        assertTrue(validator.issues(a, LocalDateTime.of(2026,11,19,9,0)).stream().anyMatch(s -> s.startsWith("end：")));
    }
}
