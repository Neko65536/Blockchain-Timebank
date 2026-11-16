package org.example.timecoinweb.config;

import org.example.utils.AliOSSUtils;
import org.example.utils.DistanceUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonConfig {

    @Bean
    public org.springframework.beans.factory.InitializingBean jwtSecret(
            @org.springframework.beans.factory.annotation.Value("${security.jwt-secret:}") String secret) {
        return () -> org.example.utils.JwtUtils.configureSecret(secret);
    }

    @Bean
    public AliOSSUtils aliOSSUtils(){
        return new AliOSSUtils();
    }

    @Bean
    public DistanceUtils distanceUtils(){
        return new DistanceUtils();
    }

}
