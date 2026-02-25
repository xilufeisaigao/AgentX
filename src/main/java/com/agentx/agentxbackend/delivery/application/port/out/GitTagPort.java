package com.agentx.agentxbackend.delivery.application.port.out;

import com.agentx.agentxbackend.delivery.domain.model.DeliveryTag;

import java.util.List;

public interface GitTagPort {

    List<DeliveryTag> listDeliveryTagsOnMain(String sessionId);
}
