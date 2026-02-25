package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.requirement.domain.event.RequirementConfirmedEvent;
import com.agentx.agentxbackend.requirement.domain.event.RequirementHandoffRequestedEvent;

final class RequirementTicketPayloadBuilder {

    private RequirementTicketPayloadBuilder() {
    }

    static String buildArchReviewPayload(RequirementConfirmedEvent event) {
        StringBuilder payload = new StringBuilder();
        payload.append("{\"kind\":\"handoff_packet\"");
        payload.append(",\"trigger\":\"REQUIREMENT_CONFIRMED\"");
        payload.append(",\"requirement_ref\":{");
        payload.append("\"doc_id\":\"").append(escapeJson(event.docId())).append("\",");
        payload.append("\"version\":").append(event.confirmedVersion());
        payload.append("}");
        payload.append(",\"requirement_delta\":{");
        payload.append("\"from_confirmed_version\":");
        if (event.previousConfirmedVersion() == null) {
            payload.append("null");
        } else {
            payload.append(event.previousConfirmedVersion());
        }
        payload.append(",\"to_version\":").append(event.confirmedVersion());
        payload.append(",\"summary\":\"")
            .append(escapeJson("Requirement baseline confirmed at version " + event.confirmedVersion()))
            .append("\"");
        payload.append("}");
        payload.append("}");
        return payload.toString();
    }

    static String buildArchReviewEventData(RequirementConfirmedEvent event) {
        StringBuilder payload = new StringBuilder();
        payload.append("{\"trigger\":\"REQUIREMENT_CONFIRMED\"");
        payload.append(",\"doc_id\":\"").append(escapeJson(event.docId())).append("\"");
        payload.append(",\"to_version\":").append(event.confirmedVersion());
        payload.append("}");
        return payload.toString();
    }

    static String buildHandoffPayload(RequirementHandoffRequestedEvent event) {
        StringBuilder payload = new StringBuilder();
        payload.append("{\"kind\":\"handoff_packet\"");
        payload.append(",\"trigger\":\"ARCHITECTURE_CHANGE_REQUESTED\"");
        if (event.requirementDocId() != null && !event.requirementDocId().isBlank()
            && event.requirementDocVersion() != null) {
            payload.append(",\"requirement_ref\":{");
            payload.append("\"doc_id\":\"").append(escapeJson(event.requirementDocId())).append("\",");
            payload.append("\"version\":").append(event.requirementDocVersion());
            payload.append("}");
        }
        payload.append(",\"user_change\":{");
        payload.append("\"summary\":\"").append(escapeJson(summaryForHandoff(event))).append("\",");
        payload.append("\"raw_user_text\":\"").append(escapeJson(event.userInput())).append("\"");
        payload.append("}");
        if (event.reason() != null && !event.reason().isBlank()) {
            payload.append(",\"question_for_architect\":[\"")
                .append(escapeJson(event.reason()))
                .append("\"]");
        }
        payload.append("}");
        return payload.toString();
    }

    static String buildHandoffEventData(RequirementHandoffRequestedEvent event) {
        StringBuilder payload = new StringBuilder();
        payload.append("{\"trigger\":\"ARCHITECTURE_CHANGE_REQUESTED\"");
        if (event.reason() != null && !event.reason().isBlank()) {
            payload.append(",\"reason\":\"").append(escapeJson(event.reason())).append("\"");
        }
        payload.append("}");
        return payload.toString();
    }

    private static String summaryForHandoff(RequirementHandoffRequestedEvent event) {
        if (event.reason() != null && !event.reason().isBlank()) {
            return event.reason();
        }
        return "User requested architecture-layer change";
    }

    private static String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
    }
}
