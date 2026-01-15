package com.example.demo.graph;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration(proxyBeanMethods = false)
public class AgentGraph {

    private static final Logger logger = LoggerFactory.getLogger(AgentGraph.class);

    private final ChatClient chatClient;
    private final BaseCheckpointSaver saver;

    public AgentGraph(ChatClient.Builder chatClientBuilder, BaseCheckpointSaver saver) {
        this.chatClient = chatClientBuilder.build();
        this.saver = saver;
    }

    @Bean
    public CompiledGraph agentGraphBean() throws GraphStateException {
        StateGraph graph = new StateGraph();

        // Flujo: Inicio -> Paso 1 -> Interrupción -> Feedback -> Paso 3 -> Fin
        graph.addNode("step1", this::step1Node);         // Genera borrador
        graph.addNode("humanFeedback", this::feedbackNode); // Procesa/Registra feedback
        graph.addNode("step3", this::step3Node);         // Refina respuesta

        graph.addEdge(StateGraph.START, "step1");
        graph.addEdge("step1", "humanFeedback");
        graph.addEdge("humanFeedback", "step3");
        graph.addEdge("step3", StateGraph.END);

        SaverConfig saverConfig = new SaverConfig().register(saver);
        CompileConfig compileConfig = CompileConfig.builder()
                .saverConfig(saverConfig)
                .interruptBefore("humanFeedback") // PUNTO CLAVE: Interrupción antes del feedback
                .build();

        return graph.compile(compileConfig);
    }

    // Paso 1: Generar una respuesta inicial (Borrador)
    private CompletableFuture<Map<String, Object>> step1Node(OverAllState state) {
        logger.info("\n\n" +
                "=================================================\n" +
                "   EXECUTING STEP 1: GENERATING DRAFT\n" +
                "=================================================\n");
        List<?> rawMessages = (List<?>) state.data().get("messages");
        List<Message> messages = convertToMessages(rawMessages);
        
        // Añadimos instrucción de sistema para este paso
        List<Message> promptMessages = new ArrayList<>(messages);
        promptMessages.add(new SystemMessage("Eres un asistente que genera un BORRADOR inicial. Sé conciso."));

        String response = chatClient.prompt()
                .messages(promptMessages)
                .call()
                .content();

        logger.info("--> STEP 1 OUTPUT: {}", response); 

        List<Message> newMessages = new ArrayList<>(messages);
        newMessages.add(new AssistantMessage("BORRADOR: " + response + "\n\n(Esperando feedback humano...)"));

        return CompletableFuture.completedFuture(Map.of("messages", newMessages));
    }

    // Paso 2: Nodo de Feedback (Se ejecuta DESPUÉS de reanudar la interrupción)
    private CompletableFuture<Map<String, Object>> feedbackNode(OverAllState state) {
        logger.info("\n\n" +
                "=================================================\n" +
                "   EXECUTING HUMAN FEEDBACK NODE (RESUMING)\n" +
                "=================================================\n");
        // Este nodo se ejecuta después de que el usuario ha 'desbloqueado' el grafo con su nuevo input.
        // El input del usuario ya habrá sido añadido al estado por el controlador antes de llegar aquí.
        // Podríamos loguear o validar el feedback aquí.

        // Log user feedback
        List<?> rawMessages = (List<?>) state.data().get("messages");
        List<Message> messages = convertToMessages(rawMessages);
        String feedback = "";
        if (!messages.isEmpty() && messages.get(messages.size() - 1) instanceof UserMessage) {
            feedback = ((UserMessage) messages.get(messages.size() - 1)).getText();
        }
        logger.info("--> USER FEEDBACK RECEIVED: {}", feedback);

        return CompletableFuture.completedFuture(Map.of());
    }

    // Paso 3: Generar respuesta final (Refinamiento)
    private CompletableFuture<Map<String, Object>> step3Node(OverAllState state) {
        logger.info("\n\n" +
                "=================================================\n" +
                "   EXECUTING STEP 3: REFINEMENT\n" +
                "=================================================\n");
        List<?> rawMessages = (List<?>) state.data().get("messages");
        
        // DEBUG: Print raw messages structure
        if (rawMessages != null) {
            logger.info("--- DEBUG: Raw Messages in Step 3 (Size: {}) ---", rawMessages.size());
            for (int i = 0; i < rawMessages.size(); i++) {
                Object msg = rawMessages.get(i);
                logger.info("Msg [{}]: Class={}, Content={}", i, msg.getClass().getName(), msg.toString());
            }
        } else {
             logger.info("--- DEBUG: Raw Messages in Step 3 is NULL ---");
        }

        List<Message> messages = convertToMessages(rawMessages);

        // DEBUG: Print converted messages
        logger.info("--- DEBUG: Converted Messages in Step 3 ---");
        for (int i = 0; i < messages.size(); i++) {
             Message m = messages.get(i);
             logger.info("Msg [{}]: Type={}, Text='{}'", i, m.getMessageType(), m.getText());
        }

        List<Message> promptMessages = new ArrayList<>(messages);
        promptMessages.add(new SystemMessage("Eres un editor experto. Usando el historial (Borrador + Feedback del usuario), genera la versión FINAL y pulida. NO empieces con 'BORRADOR'. Empieza con 'FINAL:'."));

        String response = chatClient.prompt()
                .messages(promptMessages)
                .call()
                .content();

        logger.info("--> STEP 3 FINAL OUTPUT: {}", response);

        List<Message> newMessages = new ArrayList<>(messages);
        newMessages.add(new AssistantMessage("FINAL: " + response));

        return CompletableFuture.completedFuture(Map.of("messages", newMessages));
    }


    // Helper to fix LinkedHashMap issue
    private List<Message> convertToMessages(List<?> input) {
        if (input == null) return new ArrayList<>();
        return input.stream().map(item -> {
            if (item instanceof Message) {
                return (Message) item;
            } else if (item instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) item;
                String role = (String) map.get("messageType");
                String content = (String) map.get("content");
                
                if (role == null) {
                     return new UserMessage(content != null ? content : "");
                }

                switch (role.toUpperCase()) {
                    case "USER": return new UserMessage(content);
                    case "ASSISTANT": return new AssistantMessage(content);
                    case "SYSTEM": return new SystemMessage(content);
                    default: return new UserMessage(content);
                }
            }
            return new UserMessage(item.toString());
        }).collect(Collectors.toList());
    }
}
