package com.panda.backend.feature.connect.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitHubConnection {
    private String owner;
    private String repo;
    private String branch;
    private String token;
}
