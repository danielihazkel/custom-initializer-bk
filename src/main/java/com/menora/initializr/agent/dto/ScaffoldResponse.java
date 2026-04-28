package com.menora.initializr.agent.dto;

import java.util.List;

/**
 * Envelope returned by {@code POST /agent/scaffold}. {@link #manifest()} is
 * the parsed view of the {@code .menora-init.json} that the agent will find at
 * the project root in {@link #files()}.
 */
public record ScaffoldResponse(MenoraInitManifest manifest, List<ScaffoldFile> files) {
}
