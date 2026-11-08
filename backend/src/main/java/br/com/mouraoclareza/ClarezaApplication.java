package br.com.mouraoclareza;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
public class ClarezaApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClarezaApplication.class, args);
    }

    @Bean
    public Clock applicationClock() {
        return Clock.systemUTC();
    }
}
