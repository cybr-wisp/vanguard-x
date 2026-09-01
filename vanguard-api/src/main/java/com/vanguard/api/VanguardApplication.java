package com.vanguard.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VanguardApplication {
    public static void main(String[] args) {
        SpringApplication.run(VanguardApplication.class, args);
    }
}
