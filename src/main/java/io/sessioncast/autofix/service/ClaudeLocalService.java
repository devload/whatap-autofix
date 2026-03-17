package io.sessioncast.autofix.service;

import io.sessioncast.core.api.LlmChatRequest;
import io.sessioncast.core.api.LlmChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Claude Code 로컬 CLI를 직접 호출하는 서비스.
 * SessionCast relay 없이 로컬에 설치된 claude CLI로 LLM 분석 수행.
 * sessioncast가 설치되어 있으면 sessioncast cmd를 통해, 아니면 claude를 직접 호출.
 */
@Slf4j
@Service
public class ClaudeLocalService {

    private boolean claudeAvailable = false;
    private boolean sessioncastAvailable = false;
    private String claudePath = "claude";

    @PostConstruct
    public void init() {
        // 1) sessioncast CLI 확인
        sessioncastAvailable = checkCommand("sessioncast", "--version");
        if (sessioncastAvailable) {
            log.info("SessionCast CLI 감지됨 — 로컬 모드 사용 가능");
        } else {
            log.info("SessionCast CLI 미설치 — 자동 설치 시도...");
            installSessionCast();
            sessioncastAvailable = checkCommand("sessioncast", "--version");
        }

        // 2) claude CLI 확인
        claudeAvailable = checkCommand("claude", "--version");
        if (!claudeAvailable) {
            // PATH에 없을 수 있으므로 일반적인 경로 확인
            for (String path : new String[]{
                    System.getProperty("user.home") + "/.claude/local/claude",
                    "/usr/local/bin/claude",
                    System.getProperty("user.home") + "/.npm/bin/claude"
            }) {
                if (new File(path).canExecute()) {
                    claudePath = path;
                    claudeAvailable = true;
                    break;
                }
            }
        }

        if (claudeAvailable) {
            log.info("Claude Code CLI 감지됨 — 로컬 LLM 분석 가능 (path: {})", claudePath);
        } else {
            log.warn("Claude Code CLI 미설치 — GLM 또는 SessionCast relay를 사용하세요");
        }
    }

    public boolean isAvailable() {
        return claudeAvailable;
    }

    public boolean isSessionCastAvailable() {
        return sessioncastAvailable;
    }

    /**
     * Claude Code CLI를 로컬에서 직접 호출하여 LLM 분석 수행.
     * LlmChatRequest를 프롬프트로 변환 → claude -p "..." --output-format json 실행.
     */
    public CompletableFuture<LlmChatResponse> chat(LlmChatRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // system + user 메시지를 단일 프롬프트로 결합
                StringBuilder prompt = new StringBuilder();
                for (var msg : request.messages()) {
                    if ("system".equals(msg.role())) {
                        prompt.append("[시스템 지시] ").append(msg.content()).append("\n\n");
                    } else if ("user".equals(msg.role())) {
                        prompt.append(msg.content());
                    }
                }

                String promptText = prompt.toString();
                log.debug("Claude local 호출 ({}자 프롬프트)", promptText.length());

                // claude CLI 직접 호출 — stdin으로 프롬프트 전달 (긴 프롬프트 지원)
                ProcessBuilder pb = new ProcessBuilder(claudePath, "-p", "-", "--output-format", "text");

                pb.redirectErrorStream(true);
                pb.environment().putAll(System.getenv());

                Process process = pb.start();

                // stdin에 프롬프트 전달
                try (var writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                    writer.write(promptText);
                    writer.flush();
                }

                // 출력 읽기
                String output;
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    output = sb.toString().trim();
                }

                boolean finished = process.waitFor(5, TimeUnit.MINUTES);
                if (!finished) {
                    process.destroyForcibly();
                    return errorResponse("Claude CLI 타임아웃 (5분)");
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    log.warn("Claude CLI exit code: {} output: {}", exitCode, output.substring(0, Math.min(200, output.length())));
                    return errorResponse("Claude CLI 오류 (exit: " + exitCode + ")");
                }

                log.info("Claude local 응답 수신 ({}자)", output.length());
                return buildResponse(output);

            } catch (Exception e) {
                log.error("Claude local 호출 실패", e);
                return errorResponse("Claude local 호출 실패: " + e.getMessage());
            }
        });
    }

    private LlmChatResponse buildResponse(String content) {
        return new LlmChatResponse(
                "claude-local",
                "claude-code",
                java.util.List.of(new LlmChatResponse.Choice(
                        0,
                        new LlmChatResponse.ChoiceMessage("assistant", content),
                        "stop"
                )),
                null,
                null
        );
    }

    private LlmChatResponse errorResponse(String message) {
        return new LlmChatResponse(null, null, null, null,
                new LlmChatResponse.Error(message, "local_error"));
    }

    private String escapeForShell(String text) {
        // 셸 인젝션 방지를 위해 작은따옴표로 감싸고 내부 작은따옴표 이스케이프
        return "'" + text.replace("'", "'\\''") + "'";
    }

    private boolean checkCommand(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.environment().putAll(System.getenv());
            Process p = pb.start();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void installSessionCast() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            ProcessBuilder pb;
            if (os.contains("mac") || os.contains("linux")) {
                pb = new ProcessBuilder("bash", "-c",
                        "eval \"$(curl -fsSL https://raw.githubusercontent.com/sessioncast/sessioncast-cli-release/main/install.sh)\"");
            } else if (os.contains("win")) {
                pb = new ProcessBuilder("powershell", "-Command",
                        "irm https://raw.githubusercontent.com/sessioncast/sessioncast-cli-release/main/install.ps1 | iex");
            } else {
                log.warn("SessionCast 자동 설치 미지원 OS: {}", os);
                return;
            }

            pb.redirectErrorStream(true);
            pb.environment().putAll(System.getenv());
            Process p = pb.start();

            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[SessionCast Install] {}", line);
                }
            }

            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                log.info("SessionCast CLI 설치 완료");
            } else {
                log.warn("SessionCast CLI 설치 실패 (exit: {})", finished ? p.exitValue() : "timeout");
            }
        } catch (Exception e) {
            log.warn("SessionCast CLI 설치 오류: {}", e.getMessage());
        }
    }
}
