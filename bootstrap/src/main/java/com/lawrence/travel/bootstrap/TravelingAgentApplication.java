package com.lawrence.travel.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.lawrence.travel")
public class TravelingAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(TravelingAgentApplication.class, args);
    }
}
