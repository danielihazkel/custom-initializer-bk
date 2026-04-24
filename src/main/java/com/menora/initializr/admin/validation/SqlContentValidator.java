package com.menora.initializr.admin.validation;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SqlContentValidator implements ContentSyntaxValidator {

    @Override
    public List<String> validate(String content) {
        if (content == null || content.isBlank()) return List.of();
        try {
            CCJSqlParserUtil.parseStatements(content);
            return List.of();
        } catch (JSQLParserException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String msg = cause.getMessage();
            return List.of(msg != null ? msg.trim() : e.toString());
        }
    }
}
