package com.menora.initializr.ai;

import java.util.List;
import java.util.Map;

/**
 * Wire-format records shared between {@link AiController} and {@link
 * AiFileGenerationService}.
 */
public final class AiDtos {

    private AiDtos() {}

    /** Subset of {@code ProjectFormValues} from the UI — only what the AI prompt needs. */
    public record ProjectFormDto(
            String groupId,
            String artifactId,
            String name,
            String description,
            String packageName,
            String bootVersion,
            String javaVersion,
            String packaging
    ) {}

    /** Inbound request to {@code POST /ai/generate-files}. */
    public record AiGenerationRequest(
            ProjectFormDto form,
            List<String> dependencies,
            Map<String, List<String>> selectedOptions,
            String prompt
    ) {}

    /** A single file the AI proposed adding to the project. */
    public record GeneratedAiFile(String path, String content) {}

    /** Response to the UI — the kept files are echoed back into {@code /starter-wizard.zip}. */
    public record AiGenerationResponse(List<GeneratedAiFile> files) {}
}
