package cumulocity.microservice.tcpagent.tcp.model;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class MeasurementSeries { 

        private double value;
        private String unit;
}