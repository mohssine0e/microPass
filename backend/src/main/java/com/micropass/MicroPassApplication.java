package com.micropass;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class MicroPassApplication {

    public static void main(String[] args) {
        SpringApplication.run(MicroPassApplication.class, args);
    }
}

