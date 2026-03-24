package co.com.synthax.bet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AplicacionEstadisticasApuesta {

    public static void main(String[] args) {
        SpringApplication.run(AplicacionEstadisticasApuesta.class, args);
    }
}
