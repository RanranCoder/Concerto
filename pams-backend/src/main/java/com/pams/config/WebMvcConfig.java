package com.pams.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final String uploadDir;
    private final String[] corsOriginPatterns;

    public WebMvcConfig(@Value("${pams.upload-dir}") String uploadDir,
                        @Value("${pams.cors-origin-patterns:http://localhost:*,http://127.0.0.1:*,http://172.18.38.131:*,https://dangjian.yuanlab.work,http://dangjian.yuanlab.work,https://pams.ranran.org.cn,https://*.ranran.org.cn}") String corsOriginPatterns) {
        this.uploadDir = uploadDir;
        this.corsOriginPatterns = corsOriginPatterns.split("\\s*,\\s*");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(corsOriginPatterns)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + java.nio.file.Path.of(uploadDir).toAbsolutePath().normalize() + "/");
    }
}
