package tn.vas.dcb;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tn.vas.config.DcbProperties;

@Configuration
public class DcbConfig {
    @Bean
    ChargingGateway chargingGateway(DcbProperties props) {
        return "http".equalsIgnoreCase(props.mode()) ? new HttpChargingGateway(props) : new SimulatedChargingGateway();
    }
}
