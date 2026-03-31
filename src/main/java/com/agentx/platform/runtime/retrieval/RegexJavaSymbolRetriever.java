package com.agentx.platform.runtime.retrieval;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RegexJavaSymbolRetriever implements SymbolRetriever {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+([\\w.*]+)\\s*;");
    private static final Pattern TYPE_PATTERN = Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern METHOD_PATTERN = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    @Override
    public List<String> symbolsFor(Path filePath, List<String> lines) {
        if (!filePath.getFileName().toString().endsWith(".java")) {
            return List.of();
        }
        Set<String> symbols = new LinkedHashSet<>();
        for (String line : lines) {
            addMatch(symbols, PACKAGE_PATTERN.matcher(line), 1);
            addMatch(symbols, IMPORT_PATTERN.matcher(line), 1);
            addMatch(symbols, TYPE_PATTERN.matcher(line), 2);
            Matcher matcher = METHOD_PATTERN.matcher(line);
            while (matcher.find()) {
                String candidate = matcher.group(1);
                if (isMethodLike(candidate)) {
                    symbols.add(candidate);
                }
            }
        }
        return List.copyOf(symbols);
    }

    private void addMatch(Set<String> symbols, Matcher matcher, int group) {
        if (matcher.find()) {
            symbols.add(matcher.group(group));
        }
    }

    private boolean isMethodLike(String candidate) {
        return !candidate.equals("if")
                && !candidate.equals("for")
                && !candidate.equals("while")
                && !candidate.equals("switch")
                && !candidate.equals("catch")
                && !candidate.equals("return")
                && !candidate.equals("new");
    }
}
