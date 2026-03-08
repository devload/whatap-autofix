package io.sessioncast.autofix.client;

import io.sessioncast.autofix.config.AutofixProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.Map;

@Slf4j
@Component
public class GithubApiClient {

    private final WebClient webClient;
    private final AutofixProperties.GithubProps config;

    public GithubApiClient(@Qualifier("githubWebClient") WebClient webClient,
                           AutofixProperties props) {
        this.webClient = webClient;
        this.config = props.getGithub();
    }

    public Mono<Map> getFileContent(String path) {
        return webClient.get()
                .uri("/repos/{owner}/{repo}/contents/{path}", config.getOwner(), config.getRepo(), path)
                .retrieve()
                .bodyToMono(Map.class)
                .doOnError(e -> log.error("Failed to get file: {}", path, e));
    }

    public Mono<Map> createBranch(String branchName, String fromSha) {
        Map<String, Object> body = Map.of(
                "ref", "refs/heads/" + branchName,
                "sha", fromSha
        );
        return webClient.post()
                .uri("/repos/{owner}/{repo}/git/refs", config.getOwner(), config.getRepo())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .doOnError(e -> log.error("Failed to create branch: {}", branchName, e));
    }

    public Mono<Map> updateFile(String path, String content, String message,
                                String branch, String currentSha) {
        Map<String, Object> body = Map.of(
                "message", message,
                "content", Base64.getEncoder().encodeToString(content.getBytes()),
                "sha", currentSha,
                "branch", branch
        );
        return webClient.put()
                .uri("/repos/{owner}/{repo}/contents/{path}", config.getOwner(), config.getRepo(), path)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .doOnError(e -> log.error("Failed to update file: {}", path, e));
    }

    public Mono<Map> createPullRequest(String title, String body, String head, String base) {
        Map<String, Object> prBody = Map.of(
                "title", title,
                "body", body,
                "head", head,
                "base", base
        );
        return webClient.post()
                .uri("/repos/{owner}/{repo}/pulls", config.getOwner(), config.getRepo())
                .bodyValue(prBody)
                .retrieve()
                .bodyToMono(Map.class)
                .doOnError(e -> log.error("Failed to create PR: {}", title, e));
    }

    public Mono<Map> getMainBranchRef() {
        return webClient.get()
                .uri("/repos/{owner}/{repo}/git/ref/heads/{branch}",
                        config.getOwner(), config.getRepo(), config.getDefaultBranch())
                .retrieve()
                .bodyToMono(Map.class);
    }
}
