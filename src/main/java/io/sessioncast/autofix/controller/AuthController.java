package io.sessioncast.autofix.controller;

import io.sessioncast.autofix.agent.ScoutAgent;
import io.sessioncast.autofix.client.WhatapAuthClient;
import io.sessioncast.autofix.client.WhatapAuthClient.LoginResult;
import io.sessioncast.autofix.client.WhatapAuthClient.ProjectInfo;
import io.sessioncast.autofix.config.AutofixProperties;
import io.sessioncast.autofix.config.WebClientConfig;
import io.sessioncast.core.SessionCastClient;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * WhaTap 계정 로그인 → API 토큰 자동 설정 → 프로젝트 선택 API.
 * 토큰 없이 이메일/비밀번호로도 서비스를 시작할 수 있게 해준다.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final WhatapAuthClient whatapAuthClient;
    private final AutofixProperties props;
    private final WebClientConfig webClientConfig;
    private final ScoutAgent scoutAgent;

    @Autowired(required = false)
    private SessionCastClient sessionCastClient;

    /**
     * WhaTap 이메일/비밀번호 로그인 → API 토큰 획득 + 프로젝트 목록 반환
     */
    @PostMapping("/whatap/login")
    public ResponseEntity<?> whatapLogin(@RequestBody LoginRequest request) {
        if (request.getEmail() == null || request.getPassword() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "이메일과 비밀번호를 입력해주세요"));
        }

        try {
            LoginResult loginResult = whatapAuthClient.login(request.getEmail(), request.getPassword()).block();

            if (loginResult == null || !loginResult.isSuccess()) {
                String msg = loginResult != null && loginResult.getMessage() != null
                        ? loginResult.getMessage()
                        : "로그인에 실패했습니다. 이메일/비밀번호를 확인해주세요.";
                return ResponseEntity.status(401).body(Map.of("error", msg));
            }

            // 계정 레벨 API 토큰 저장
            String accountToken = loginResult.getApiToken();
            log.info("WhaTap 계정 토큰 획득 완료 (accountId: {})", loginResult.getAccountId());

            // 프로젝트 목록 조회
            List<ProjectInfo> projects = whatapAuthClient.getProjects(accountToken).block();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "accountId", loginResult.getAccountId(),
                    "accountToken", maskToken(accountToken),
                    "projects", projects != null ? projects : List.of()
            ));
        } catch (Exception e) {
            log.error("WhaTap 로그인 처리 중 오류", e);
            return ResponseEntity.status(500).body(Map.of("error", "로그인 처리 중 오류: " + e.getMessage()));
        }
    }

    /**
     * 프로젝트 선택 → pcode + 프로젝트 토큰으로 모니터링 시작
     */
    @PostMapping("/whatap/select-project")
    public ResponseEntity<?> selectProject(@RequestBody SelectProjectRequest request) {
        if (request.getPcode() == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "pcode가 필요합니다"));
        }

        // projectApiToken이 없으면 계정 토큰으로 대체
        if (request.getProjectApiToken() == null || request.getProjectApiToken().isBlank()) {
            String accountToken = whatapAuthClient.getAccountApiToken();
            if (accountToken != null && !accountToken.isBlank()) {
                request.setProjectApiToken(accountToken);
                log.info("프로젝트 토큰 없음 → 계정 토큰으로 대체");
            }
        }

        // AutofixProperties 업데이트
        String tokenToUse = request.getProjectApiToken();
        log.info("프로젝트 선택 — pcode: {}, token: {}, productType: {}",
                request.getPcode(),
                tokenToUse != null && tokenToUse.length() > 8
                        ? tokenToUse.substring(0, 4) + "***" + tokenToUse.substring(tokenToUse.length() - 4)
                        : "(empty)",
                request.getProductType());
        props.getWhatap().setApiToken(tokenToUse);
        props.getWhatap().setPcode(String.valueOf(request.getPcode()));
        props.getWhatap().setProjectName(request.getProjectName());
        if (request.getProductType() != null && !request.getProductType().isBlank()) {
            props.getWhatap().setProductType(request.getProductType());
        }

        // WebClient 재구성
        webClientConfig.refreshWhatapClient(props);

        // 프로젝트 변경 시 메트릭 프로파일 초기화 (AI가 재탐색)
        scoutAgent.resetProfile();

        String productType = props.getWhatap().getProductType();
        log.info("WhaTap 프로젝트 선택 완료 — pcode: {}, name: {}, type: {}",
                request.getPcode(), request.getProjectName(), productType);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "pcode", request.getPcode(),
                "projectName", request.getProjectName() != null ? request.getProjectName() : "",
                "productType", productType != null ? productType : "java",
                "message", "프로젝트 연결 완료! Scout Agent가 모니터링을 시작합니다."
        ));
    }

    /**
     * SessionCast 연결 상태 확인 (Spring Boot Starter 자동 관리)
     */
    @GetMapping("/sessioncast/status")
    public Map<String, Object> getSessionCastStatus() {
        boolean connected = sessionCastClient != null && sessionCastClient.isConnected();
        return Map.of("connected", connected);
    }

    /**
     * 로그아웃 — 모든 토큰/연결 정보 초기화
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        props.getWhatap().setApiToken("");
        props.getWhatap().setPcode("");
        log.info("로그아웃 완료 — WhaTap 연결 정보 초기화됨");
        return ResponseEntity.ok(Map.of("success", true, "message", "로그아웃되었습니다."));
    }

    /**
     * 현재 연결 상태 확인
     */
    @GetMapping("/whatap/status")
    public Map<String, Object> getConnectionStatus() {
        boolean tokenSet = props.getWhatap().getApiToken() != null
                && !props.getWhatap().getApiToken().isBlank();
        boolean pcodeSet = props.getWhatap().getPcode() != null
                && !props.getWhatap().getPcode().isBlank();

        return Map.of(
                "connected", tokenSet && pcodeSet,
                "pcode", pcodeSet ? props.getWhatap().getPcode() : "",
                "tokenConfigured", tokenSet,
                "productType", props.getWhatap().getProductType() != null ? props.getWhatap().getProductType() : "java",
                "projectName", props.getWhatap().getProjectName() != null ? props.getWhatap().getProjectName() : ""
        );
    }

    private String maskToken(String token) {
        if (token == null || token.length() < 8) return "***";
        return token.substring(0, 4) + "***" + token.substring(token.length() - 4);
    }

    @Data
    public static class LoginRequest {
        private String email;
        private String password;
    }

    @Data
    public static class SelectProjectRequest {
        private long pcode;
        private String projectApiToken;
        private String projectName;
        private String productType;  // java, browser, nodejs, python, etc.
    }
}
