package com.menora.initializr.config;

import com.menora.initializr.config.ProjectPreviewController.TreeNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the hierarchical {@link TreeNode} list used by the preview endpoints
 * from a flat, sorted list of relative file paths (forward-slash separated).
 *
 * <p>Shared by {@link ProjectPreviewController} and {@link FrontendStarterController}
 * so both preview JSON responses produce the same tree shape.
 */
final class PreviewTreeBuilder {

    private PreviewTreeBuilder() {}

    static List<TreeNode> buildTree(List<String> sortedPaths) {
        return buildChildren("", sortedPaths);
    }

    private static List<TreeNode> buildChildren(String prefix, List<String> paths) {
        Map<String, List<String>> subdirs = new LinkedHashMap<>();
        List<String> directFiles = new ArrayList<>();

        for (String path : paths) {
            String relative = prefix.isEmpty() ? path : path.substring(prefix.length() + 1);
            int slash = relative.indexOf('/');
            if (slash == -1) {
                directFiles.add(path);
            } else {
                String childDir = relative.substring(0, slash);
                String childPrefix = prefix.isEmpty() ? childDir : prefix + "/" + childDir;
                subdirs.computeIfAbsent(childPrefix, k -> new ArrayList<>()).add(path);
            }
        }

        List<TreeNode> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : subdirs.entrySet()) {
            String dirPath = entry.getKey();
            String dirName = dirPath.contains("/") ? dirPath.substring(dirPath.lastIndexOf('/') + 1) : dirPath;
            result.add(new TreeNode(dirName, dirPath, "directory", buildChildren(dirPath, entry.getValue())));
        }
        for (String filePath : directFiles) {
            String fileName = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
            result.add(new TreeNode(fileName, filePath, "file", List.of()));
        }
        return result;
    }
}
