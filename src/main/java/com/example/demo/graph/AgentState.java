package com.example.demo.graph;

import org.springframework.ai.chat.messages.Message;
import java.util.List;
import java.util.ArrayList;

public record AgentState(List<Message> messages) {
    public AgentState {
        if (messages == null) {
            messages = new ArrayList<>();
        }
    }
}
