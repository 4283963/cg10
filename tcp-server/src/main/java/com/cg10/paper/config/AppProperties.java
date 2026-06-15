package com.cg10.paper.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Cylinder cylinder = new Cylinder();
    private Compensation compensation = new Compensation();
    private Websocket websocket = new Websocket();

    @Data
    public static class Cylinder {
        private int count = 40;
        private int scanPoints = 64;
        private double baseTemperature = 105.0;
        private double lowThreshold = 95.0;
        private double highThreshold = 130.0;
    }

    @Data
    public static class Compensation {
        private double maxValveAdjustment = 15.0;
        private double minValveAdjustment = -15.0;
        private double controlGain = 0.8;
        private int zoneCount = 8;
    }

    @Data
    public static class Websocket {
        private int broadcastIntervalMs = 100;
    }
}
