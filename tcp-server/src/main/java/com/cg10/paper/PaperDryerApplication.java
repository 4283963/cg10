package com.cg10.paper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PaperDryerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaperDryerApplication.class, args);
    }
}
