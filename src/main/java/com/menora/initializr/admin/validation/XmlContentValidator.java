package com.menora.initializr.admin.validation;

import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.List;

@Component
public class XmlContentValidator implements ContentSyntaxValidator {

    @Override
    public List<String> validate(String content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            builder.parse(new InputSource(new StringReader(content)));
            return List.of();
        } catch (SAXParseException e) {
            return List.of("line " + e.getLineNumber() + ": " + e.getMessage());
        } catch (Exception e) {
            return List.of(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }
}
