package com.panda.backend.feature.connect.application;

import org.kohsuke.github.GitHub;
import org.kohsuke.github.GHRepository;
import org.springframework.stereotype.Service;

@Service
public class GitHubConnectionService {

    public void validateAndConnectGitHub(String token, String owner, String repo) throws Exception {
        GitHub github = GitHub.connectUsingOAuth(token);
        GHRepository repository = github.getRepository(owner + "/" + repo);

        if (repository == null) {
            throw new IllegalArgumentException("Repository not found: " + owner + "/" + repo);
        }
    }
}
