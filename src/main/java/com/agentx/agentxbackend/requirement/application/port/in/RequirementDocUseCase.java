package com.agentx.agentxbackend.requirement.application.port.in;

import com.agentx.agentxbackend.requirement.domain.model.RequirementDoc;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocVersion;

public interface RequirementDocUseCase {

    RequirementDoc createRequirementDoc(String sessionId, String title);

    RequirementDocVersion createVersion(String docId, String content, String createdByRole);

    RequirementDoc confirm(String docId);
}
