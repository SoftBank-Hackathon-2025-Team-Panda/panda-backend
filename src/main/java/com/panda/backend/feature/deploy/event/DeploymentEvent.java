package com.panda.backend.feature.deploy.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeploymentEvent {
    private String type;
    private String message;
    private Map<String, Object> details;
}
