package io.sessioncast.autofix;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.sessioncast")
@EnableScheduling
public class AutofixApplication {
    public static void main(String[] args) {
        SpringApplication.run(AutofixApplication.class, args);
    }
}
