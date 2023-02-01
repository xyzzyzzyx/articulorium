package com.articulorum.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = "com.articulorum" )
public class LdpApplication {

    public static void main(String[] args) {
        SpringApplication.run(LdpApplication.class, args);
    }

}
