package com.agentx.platform.runtime.retrieval;

import com.agentx.platform.runtime.context.ContextCompilationRequest;
import com.agentx.platform.runtime.context.FactBundle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class DefaultRetrievalQueryPlanner implements RetrievalQueryPlanner {

    @Override
    public RetrievalQuery plan(ContextCompilationRequest request, FactBundle factBundle) {
        Set<String> terms = new LinkedHashSet<>();
        Set<String> preferredPaths = new LinkedHashSet<>();
        collectTerms(factBundle.sections(), terms, preferredPaths);
        if (request.scope().taskId() != null) {
            terms.add(request.scope().taskId());
        }
        if (request.scope().originNodeId() != null) {
            terms.add(request.scope().originNodeId());
        }
        return new RetrievalQuery(List.copyOf(terms).stream().limit(16).toList(), List.copyOf(preferredPaths).stream().limit(12).toList());
    }

    private void collectTerms(Object value, Set<String> terms, Set<String> preferredPaths) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> mapValue) {
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                if (String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT).contains("writescope")) {
                    collectPaths(entry.getValue(), preferredPaths);
                }
                collectTerms(entry.getValue(), terms, preferredPaths);
            }
            return;
        }
        if (value instanceof List<?> listValue) {
            for (Object item : listValue) {
                collectTerms(item, terms, preferredPaths);
            }
            return;
        }
        String stringValue = String.valueOf(value).trim();
        if (stringValue.isBlank()) {
            return;
        }
        if (stringValue.contains("/") || stringValue.contains("\\")) {
            preferredPaths.add(stringValue.replace('\\', '/'));
        }
        tokenize(stringValue).forEach(terms::add);
    }

    private void collectPaths(Object value, Set<String> preferredPaths) {
        if (value instanceof List<?> listValue) {
            for (Object item : listValue) {
                preferredPaths.add(String.valueOf(item).replace('\\', '/'));
            }
        } else if (value != null) {
            preferredPaths.add(String.valueOf(value).replace('\\', '/'));
        }
    }

    private List<String> tokenize(String rawValue) {
        List<String> tokens = new ArrayList<>();
        for (String token : rawValue.split("[^A-Za-z0-9_./-]+")) {
            if (token.length() < 3) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }
}
