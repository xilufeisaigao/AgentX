package com.agentx.agentxbackend.session.infrastructure.external;

import com.agentx.agentxbackend.session.application.port.out.DeliveryProofPort;
import org.springframework.stereotype.Component;

@Component
public class GitDeliveryProofAdapter implements DeliveryProofPort {

    @Override
    public boolean hasAtLeastOneDeliveryTagOnMain(String sessionId) {
        return false;
    }
}
