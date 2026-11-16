package org.example.timecoinweb.config;


import org.example.timecoinweb.interceptor.LoginCheckInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final LoginCheckInterceptor loginCheckInterceptor;
    private final org.example.timecoinweb.interceptor.RedisRateLimitInterceptor rateLimitInterceptor;

    public WebMvcConfig(LoginCheckInterceptor loginCheckInterceptor, org.example.timecoinweb.interceptor.RedisRateLimitInterceptor rateLimitInterceptor) {
        this.loginCheckInterceptor = loginCheckInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    /**
     * PC 前端 devServer 在 http://localhost:7000，需带 cookie/credentials 时不能用 Allow-Origin: *。
     * allowedOriginPatterns 可与 allowCredentials 同时使用。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginCheckInterceptor)
                .addPathPatterns("/**");
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/**");
    }
}
