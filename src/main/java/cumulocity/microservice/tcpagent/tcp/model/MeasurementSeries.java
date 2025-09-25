package cumulocity.microservice.tcpagent.tcp.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class MeasurementSeries { 

        private int value;
        private String unit;
}