package com.bvisionry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class BVisionryApplication {

    public static void main(String[] args) {
        SpringApplication.run(BVisionryApplication.class, args);
    }
}
