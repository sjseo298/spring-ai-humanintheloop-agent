package com.example.demo.graph;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.file.FileSystemSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.AgentStateFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration(proxyBeanMethods = false)
public class GraphConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public BaseCheckpointSaver fileSystemSaver(ObjectMapper objectMapper) throws IOException {
        String checkpointPath = "checkpoints";
        Path path = Paths.get(checkpointPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
        
        AgentStateFactory<OverAllState> factory = OverAllState::new;
        
        return FileSystemSaver.builder()
                .targetFolder(path)
                .stateSerializer(new SpringAIJacksonStateSerializer(factory, objectMapper))
                .build();
    }
}

