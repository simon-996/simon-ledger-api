package com.simon.ledger;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.simon.ledger.mapper")
@EnableScheduling
public class SimonLedgerApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimonLedgerApiApplication.class, args);
    }
}
