package io.sessioncast.autofix.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class FixProposal {
    private FixType type;
    private String description;
    private String repository;
    private String branch;
    private List<FileDiff> diffs;
    private String scriptCommand;
    private String recommendation;      // GitHub 미연결 시 권장 조치 상세
    private boolean autoFixAvailable;    // 자동 수정 가능 여부

    public enum FixType {
        CODE_CHANGE, SCRIPT, CONFIG_CHANGE, ROLLBACK
    }

    @Data
    @Builder
    public static class FileDiff {
        private String filePath;
        private String oldContent;
        private String newContent;
        private String diff;
    }
}
