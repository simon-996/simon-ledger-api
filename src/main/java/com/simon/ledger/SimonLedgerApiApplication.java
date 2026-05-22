package com.simon.ledger;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.simon.ledger.mapper")
public class SimonLedgerApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimonLedgerApiApplication.class, args);
    }
}
