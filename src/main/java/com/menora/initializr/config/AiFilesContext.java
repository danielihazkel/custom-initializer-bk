package com.menora.initializr.config;

import com.menora.initializr.ai.AiDtos.GeneratedAiFile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-local carrier for the kept AI-generated files across the
 * project-generation child context. Structural twin of {@link
 * SqlScriptsContext} / {@link OpenApiSpecContext}.
 *
 * <p>Populated by {@code WizardStarterController.populateContexts} from the
 * request body's {@code aiFiles} list and read by the {@code aiFileContributor}
 * bean during generation. Cleared in the controller's {@code finally} block.
 */
@Component
public class AiFilesContext {

    private static final ThreadLocal<List<GeneratedAiFile>> FILES =
            ThreadLocal.withInitial(ArrayList::new);

    public void populate(List<GeneratedAiFile> files) {
        FILES.set(files == null ? new ArrayList<>() : new ArrayList<>(files));
    }

    public void clear() {
        FILES.remove();
    }

    public List<GeneratedAiFile> all() {
        return FILES.get();
    }

    public boolean isEmpty() {
        return FILES.get().isEmpty();
    }
}
