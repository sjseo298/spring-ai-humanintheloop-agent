package com.example.demo.web;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/chat")
public class AgentController {

    private final CompiledGraph agentGraph;

    public AgentController(CompiledGraph agentGraph) {
        this.agentGraph = agentGraph;
    }

    @PostMapping("/{threadId}")
    public Map<String, Object> chat(@PathVariable String threadId, @RequestBody String userInput) {
        try {
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(threadId)
                    .build();

            List<Message> messages = new ArrayList<>();
            Optional<StateSnapshot> snapshotOpt = Optional.empty();
            
            try {
                snapshotOpt = agentGraph.stateOf(config);
            } catch (Exception e) {
                System.err.println("Error checking state: " + e.getMessage());
            }

            Optional<OverAllState> result;

            if (snapshotOpt.isPresent()) {
                StateSnapshot snapshot = snapshotOpt.get();
                System.out.println("--- HITL: Found matching state for thread: " + threadId + " ---");
                System.out.println("--- HITL: Next node in snapshot: " + snapshot.next() + " ---");

                // Get the current OverAllState
                OverAllState currentState = snapshot.state();
                
                // Reconstruct history
                List<Message> history = new ArrayList<>();
                Map<String, Object> data = currentState.data();
                if (data.containsKey("messages")) {
                    List<?> raw = (List<?>) data.get("messages");
                    history.addAll(convertToMessages(raw));
                }
                
                // Detailed history log
                System.out.println("--- HITL DEBUG: Current History (" + history.size() + " messages): ---");
                history.forEach(m -> System.out.println("    - [" + m.getMessageType() + "]: " + 
                     (m.getText() != null ? m.getText().replace("\n", " ").substring(0, Math.min(m.getText().length(), 80)) + "..." : "null")));

                System.out.println("--- HITL: Adding new user input: \"" + userInput + "\" ---");
                history.add(new UserMessage(userInput));

                System.out.println("--- HITL: Updating state with new user message via agentGraph.updateState... ---");
                // Correctly update state using the graph engine which persists it and returns new config
                RunnableConfig newConfig = agentGraph.updateState(config, Map.of("messages", history));
                System.out.println("--- HITL: State updated. New Config ID: " + newConfig.checkPointId().orElse("N/A") + " ---");

                System.out.println("--- HITL: Resuming graph with new config... ---");
                // Pass the history AGAIN in the invoke just to be sure it is in the memory context for the execution
                result = agentGraph.invoke(Map.of("messages", history), newConfig);

            } else {

                // START (First Run)
                System.out.println("--- HITL: No state found. Starting new flow... ---");
                messages.add(new UserMessage(userInput));
                result = agentGraph.invoke(Map.of("messages", messages), config);
            }


            if (result.isPresent()) {
                 OverAllState state = result.get();
                 List<?> currentMessagesRaw = (List<?>) state.data().get("messages");
                 List<Message> currentMessages = convertToMessages(currentMessagesRaw);
                 
                 if (!currentMessages.isEmpty()) {
                    Message lastMsg = currentMessages.get(currentMessages.size() - 1);
                    String content;
                    if (lastMsg instanceof UserMessage) {
                        content = ((UserMessage) lastMsg).getText();
                    } else if (lastMsg instanceof AssistantMessage) {
                        content = ((AssistantMessage) lastMsg).getText();
                    } else if (lastMsg instanceof SystemMessage) {
                        content = ((SystemMessage) lastMsg).getText();
                    } else {
                        content = lastMsg.toString();
                    }
                    return Map.of("last_response", content, "thread_id", threadId);
                 }
            }
            
            return Map.of("last_response", "No response generated", "thread_id", threadId);
        } catch (Exception e) {
            e.printStackTrace();
            return Map.of("error", e.getMessage(), "trace", e.getStackTrace()[0].toString());
        }
    }


    private List<Message> convertToMessages(List<?> rawMessages) {
        if (rawMessages == null) return new ArrayList<>();
        return rawMessages.stream()
            .map(msg -> {
                if (msg instanceof Message) return (Message) msg;
                if (msg instanceof Map) {
                     Map<?,?> map = (Map<?,?>) msg;
                     String type = (String) map.get("messageType");
                     String content = (String) map.get("content");
                     if ("USER".equalsIgnoreCase(type)) return new UserMessage(content);
                     if ("ASSISTANT".equalsIgnoreCase(type)) return new AssistantMessage(content);
                     if ("SYSTEM".equalsIgnoreCase(type)) return new SystemMessage(content);
                }
                return null;
            })
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toList());
    }
}
