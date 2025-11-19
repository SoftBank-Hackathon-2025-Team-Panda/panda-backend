package com.panda.backend.feature.connect.application;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;
import software.amazon.awssdk.regions.Region;

@Service
public class AwsConnectionService {

    public void validateAwsCredentials(String region, String accessKeyId, String secretAccessKey, String sessionToken) throws Exception {
        try {
            StsClient stsClient;

            if (sessionToken != null && !sessionToken.isEmpty()) {
                AwsSessionCredentials credentials = AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken);
                stsClient = StsClient.builder()
                        .region(Region.of(region))
                        .credentialsProvider(StaticCredentialsProvider.create(credentials))
                        .build();
            } else {
                AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
                stsClient = StsClient.builder()
                        .region(Region.of(region))
                        .credentialsProvider(StaticCredentialsProvider.create(credentials))
                        .build();
            }

            GetCallerIdentityResponse response = stsClient.getCallerIdentity(GetCallerIdentityRequest.builder().build());
            stsClient.close();

            if (response == null || response.account() == null) {
                throw new IllegalArgumentException("Failed to validate AWS credentials");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("AWS credentials validation failed: " + e.getMessage());
        }
    }
}
