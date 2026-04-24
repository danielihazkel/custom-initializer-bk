package com.menora.initializr.admin.validation;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JavaContentValidator implements ContentSyntaxValidator {

    private final JavaParser parser = new JavaParser();

    @Override
    public List<String> validate(String content) {
        ParseResult<CompilationUnit> result = parser.parse(content);
        if (result.isSuccessful()) return List.of();
        return result.getProblems().stream().map(this::format).toList();
    }

    private String format(Problem p) {
        return p.getLocation()
                .flatMap(loc -> loc.toRange().map(r -> "line " + r.begin.line + ": " + p.getMessage()))
                .orElse(p.getMessage());
    }
}
