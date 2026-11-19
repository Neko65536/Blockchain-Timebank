package org.example.timecoinweb.config;

import com.github.pagehelper.PageInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;

/** 固定业务JSON契约，避免框架默认值变化破坏历史快照与幂等摘要。 */
@Configuration
public class SerializationConfig {
    @Bean
    public JsonMapper jsonMapper() {
        return JsonMapper.builder().configureForJackson2()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    @Bean
    public PageInterceptor pageInterceptor() {
        return new PageInterceptor();
    }
}
