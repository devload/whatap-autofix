package io.sessioncast.autofix.rule;

import io.sessioncast.autofix.model.Pipeline.Severity;
import lombok.Data;

@Data
public class Rule {
    private String id;
    private String name;
    private String metric;
    private String condition;
    private Severity severity;
    private boolean autoFix;
    private String fixScript;
    private String description;
}
