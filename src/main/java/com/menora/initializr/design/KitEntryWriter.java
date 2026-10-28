package com.menora.initializr.design;

/**
 * Writes the generated {@code src/main.tsx}: React's root, the kit's self-hosted font imports
 * (one per weight of its {@code @fontsource} package) and {@code src/index.css}. Both shipped
 * template sets' {@code main.tsx} are exactly this file for their font.
 */
public final class KitEntryWriter {

    private KitEntryWriter() {
    }

    public static String write(KitManifest.Fonts fonts) {
        StringBuilder out = new StringBuilder()
                .append("import React from 'react'\n")
                .append("import ReactDOM from 'react-dom/client'\n")
                .append("import App from './app/App'\n");
        if (fonts != null) {
            if (fonts.comment() != null && !fonts.comment().isBlank()) out.append("// ").append(fonts.comment()).append('\n');
            for (Integer w : fonts.weights()) out.append("import '").append(fonts.packageName()).append('/').append(w).append(".css'\n");
        }
        return out.append("import './index.css'\n")
                .append('\n')
                .append("ReactDOM.createRoot(document.getElementById('root')!).render(\n")
                .append("  <React.StrictMode>\n")
                .append("    <App />\n")
                .append("  </React.StrictMode>,\n")
                .append(")\n")
                .toString();
    }
}
