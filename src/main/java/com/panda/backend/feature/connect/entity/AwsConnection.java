package com.panda.backend.feature.connect.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AwsConnection {
    private String region;
    private String accessKeyId;
    private String secretAccessKey;
    private String sessionToken;
}
