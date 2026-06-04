package dk.dtu.ait.euroteq.autotest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class AutotestApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutotestApplication.class, args);
    }
}
