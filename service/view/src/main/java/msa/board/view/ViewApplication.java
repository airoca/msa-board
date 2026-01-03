package msa.board.view;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@EntityScan(basePackages = "msa.board")
@EnableJpaRepositories(basePackages = "msa.board")
@SpringBootApplication
public class ViewApplication {
    public static void main(String[] args) {
        SpringApplication.run(ViewApplication.class, args);
    }
}
