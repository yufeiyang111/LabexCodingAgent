package com.labex;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.labex.mapper")
public class LabexAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(LabexAgentApplication.class, args);
    }
}
