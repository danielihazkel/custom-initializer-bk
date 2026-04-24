package com.menora.initializr.admin.validation;

import java.util.List;

public interface ContentSyntaxValidator {
    List<String> validate(String content);
}
