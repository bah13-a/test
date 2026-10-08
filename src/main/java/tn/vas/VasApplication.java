package tn.vas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableJpaRepositories(considerNestedRepositories = true)
public class VasApplication {
    public static void main(String[] args) {
        SpringApplication.run(VasApplication.class, args);
    }
}
