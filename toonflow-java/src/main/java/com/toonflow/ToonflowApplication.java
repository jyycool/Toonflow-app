package com.toonflow;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@MapperScan("com.toonflow.mapper")
@EnableAsync(proxyTargetClass = true)
public class ToonflowApplication {
    public static void main(String[] args) {
        SpringApplication.run(ToonflowApplication.class, args);
    }
}
