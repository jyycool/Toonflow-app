package com.toonflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Value("${toonflow.data-dir}")
    private String dataDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/oss/**")
                .addResourceLocations("file:" + dataDir + File.separator + "oss" + File.separator);
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("file:" + dataDir + File.separator + "assets" + File.separator);
        registry.addResourceHandler("/skills/**")
                .addResourceLocations("file:" + dataDir + File.separator + "skills" + File.separator);
        registry.addResourceHandler("/**")
                .addResourceLocations("file:" + dataDir + File.separator + "web" + File.separator);
    }
}
