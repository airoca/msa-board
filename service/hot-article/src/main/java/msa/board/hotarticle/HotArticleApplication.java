package msa.board.hotarticle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@EntityScan(basePackages = "msa.board")
@SpringBootApplication
public class HotArticleApplication {
    public static void main(String[] args) {
        SpringApplication.run(HotArticleApplication.class, args);
    }
}
