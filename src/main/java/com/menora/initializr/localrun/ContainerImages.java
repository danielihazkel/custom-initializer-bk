package com.menora.initializr.localrun;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where a generated project pulls its container images from: {@code compose.yaml} and the
 * Testcontainers tests.
 *
 * <ul>
 *   <li>{@code menora.containers.hub-prefix} (default {@code repo.menora.co.il/dockerhub/}) — put in
 *       front of every Docker Hub image ({@code postgres:16-alpine}), as the generated frontend's
 *       Dockerfile already pulls nginx through that proxy. Testcontainers gets it as
 *       {@code hub.image.name.prefix}. Empty: Docker Hub itself.</li>
 *   <li>{@code menora.containers.mcr-prefix} (default {@code mcr.microsoft.com/}) — the registry of
 *       the SQL Server image, which is not on Docker Hub.</li>
 * </ul>
 */
@Component
public class ContainerImages {

    private final String hubPrefix;
    private final String mcrPrefix;

    public ContainerImages(@Value("${menora.containers.hub-prefix:repo.menora.co.il/dockerhub/}") String hubPrefix,
                           @Value("${menora.containers.mcr-prefix:mcr.microsoft.com/}") String mcrPrefix) {
        this.hubPrefix = slashed(hubPrefix);
        this.mcrPrefix = slashed(mcrPrefix);
    }

    /** The images as Docker Hub and Microsoft's registry name them, with no proxy. */
    public static ContainerImages direct() {
        return new ContainerImages("", "mcr.microsoft.com/");
    }

    /** A Docker Hub image ({@code postgres:16-alpine}) as this server's projects pull it. */
    public String hub(String image) {
        return hubPrefix + image;
    }

    /** A Microsoft registry image ({@code mssql/server:2022-latest}) as this server's projects pull it. */
    public String mcr(String image) {
        return mcrPrefix + image;
    }

    /** The prefix in front of Docker Hub images, empty for none. */
    public String hubPrefix() {
        return hubPrefix;
    }

    private static String slashed(String prefix) {
        if (prefix == null || prefix.isBlank()) return "";
        String p = prefix.trim();
        return p.endsWith("/") ? p : p + "/";
    }
}
