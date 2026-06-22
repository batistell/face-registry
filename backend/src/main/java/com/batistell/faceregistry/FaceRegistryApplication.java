package com.batistell.faceregistry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class FaceRegistryApplication {
    public static void main(String[] args) {
        SpringApplication.run(FaceRegistryApplication.class, args);
    }
}
