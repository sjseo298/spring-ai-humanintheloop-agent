---
applyTo: '**'
---
# Instrucciones para Agentes (Spring AI / Java)

Estas instrucciones definen **cómo construir aplicaciones de IA en este repositorio** (Spring Boot 4 + Spring AI 2.0.0-M1, Java 21). El objetivo es que cualquier agente implemente features de IA con **tipado fuerte, observabilidad, pruebas y orquestación robusta**.

## Stack y restricciones

- **Lenguaje/Runtime:** Java 21.
- **Framework:** Spring Boot 4.0.1.
- **IA:** Spring AI 2.0.0-M1 (BOM). No introducir SDKs “ad hoc” si Spring AI ya lo soporta.
- **Build:** Maven Wrapper (`./mvnw`).
- **Repo Milestones:** se usa `spring-milestones` por ser M1.

## Reglas de implementación

- Preferir **abstracciones de Spring AI** (p.ej. `ChatClient`, `VectorStore`, convertidores, memoria, herramientas).
- Mantener **interfaces claras** y dependencias mínimas.
- Usar **records/POJOs** tipados para entradas/salidas; evitar `Map<String,Object>` salvo capa de interoperabilidad.
- Externalizar prompts como **recursos versionados** (archivos en `src/main/resources/prompts/`), no strings gigantes embebidos.
- No hardcodear secretos (API keys). Usar variables de entorno y `application.properties`.

## Convenciones de paquetes

- Código de aplicación bajo `src/main/java/com/example/demo/`.
- Recomendado:
  - `.../ai/` (clientes, prompts, herramientas, orquestación)
  - `.../rag/` (ingesta, splitters, vector store, retrieval)
  - `.../mcp/` (servidores/clients MCP y adaptadores)
  - `.../web/` (controllers)
  - `.../eval/` (evaluación/quality gates)

## Gestión de Memoria y Contexto

- **Conceptos Separados:**
  - **Conversational Memory:** (`ChatMemory`) Para que el agente recuerde lo dicho anteriormente. Usar `MessageChatMemoryAdvisor`.
  - **Graph State Persistence:** (`Checkpointer`) Para guardar el estado *exacto* de la ejecución (variables, nodo actual) y permitir pausar/reanudar.
- **Implementación de Checkpointers (Spring AI Alibaba Graph):**
  - **Core Dependency:** Usar `spring-ai-alibaba-graph-core` (v1.1.0.0 o superior). Asegúrate de agregar la dependencia correspondiente en tu `pom.xml`.
  - **Configuración de Beans:** Anotar la clase `@Configuration` con `@Configuration(proxyBeanMethods = false)` para prevenir errores de CGLIB y referencias circulares.
  - **Persistencia (FileSystem):**
    - Usar `FileSystemSaver` con `SpringAIJacksonStateSerializer`.
    - **CRÍTICO:** Jackson deserializa JSON como `LinkedHashMap` por defecto. Es **obligatorio** implementar un conversor manual (ej. `convertToMessages`) para transformar esos mapas a POJOs de Spring AI (`UserMessage`, `ToolResponseMessage`) antes de pasarlos a los nodos del grafo.
  - **Dependencies:** No incluir starters JDBC (ej. `sqlite-jdbc`) si no hay un `DataSource` configurado, o la aplicación no arrancará.

- **Advisors vs Manual History:** En Spring AI 2.0.0-M1+, EVITAR la gestión manual de `List<Message>` solo para historial.
- Usar **`MessageChatMemoryAdvisor`** inyectándolo en el `ChatClient`.
- Para sesiones efímeras (request-scoped agents), instanciar **`MessageWindowChatMemory`** (en reemplazo de `InMemoryChatMemory`) dentro del servicio o flujo.

## Migración y Breaking Changes (2.0.0-M1)

Al trabajar con Spring AI 2.0.0-M1, tener en cuenta los siguientes cambios críticos respecto a v1.x:

1. **Temperatura por defecto eliminada:** Los modelos ya no aplican temperatura por defecto. Se debe configurar explícitamente (`spring.ai.openai.chat.options.temperature=0.7`).
2. **Modelo por defecto:** OpenAI ahora usa variantes "mini" (ej. `gpt-4o-mini` o similar) como default.
3. **Clases Renombradas/Movidas:**
   - `InMemoryChatMemory` -> **`MessageWindowChatMemory`** (para implementaciones en memoria simples).
   - TTS: `OpenAiAudioSpeechModel` -> implementa `TextToSpeechModel`. Parámetro `speed` ahora es `Double`.
4. **Advisors Builders:** Usar Builders para Advisors (`QuestionAnswerAdvisor.builder(...)`) en lugar de constructores públicos deprecados.
5. **Salida Estructurada:** Preferir siempre `ChatClient.call().entity(Class<T>)` para mapeo automático de JSON a Records.
6. **Function Calling (Tools):** La nomenclatura ha cambiado de "Functions" a "Tool Names".
   - `application.properties`: Usar `spring.ai.openai.chat.options.tool-names` en lugar de `...functions`.
   - `ChatClient.Builder`: Usar `.defaultToolNames(...)` en lugar de `.defaultFunctions(...)`.

## Prompting y salidas estructuradas (tipado fuerte)

- Guardar prompts en `src/main/resources/prompts/*.st` o `*.txt`.
- Cuando se requiera estructura:
  - Definir un `record` (o POJO) como contrato de salida.
  - Convertir la respuesta del modelo a objeto tipado con `BeanOutputConverter` (o convertidor apropiado de Spring AI).
  - Validar campos obligatorios y manejar fallos de parseo con errores claros.

## RAG empresarial (patrón)

Cuando el feature requiera conocimiento externo:

1. **Ingesta/ETL**
   - Preferir lectores/transformers de Spring AI.
   - Para documentos complejos (PDF/Office) incorporar Apache Tika si es necesario.
2. **Chunking**
   - Usar splitters provistos por Spring AI (y ajustar tamaños por tokens).
3. **Vector store**
   - Usar la abstracción `VectorStore` y un backend real (PGVector/Neo4j/Redis/etc.).
4. **Recuperación**
   - Implementar retrieval básico primero; luego mejorar con:
     - query transformation
     - reranking
     - filtros/metadatos

## Herramientas (Function Calling)

- Implementar “tools” como servicios Spring con contratos claros.
- **Integraciones:** Si un starter de Spring AI falla por conflictos de versiones (BOM), preferir implementar el cliente manualmente con `RestClient` (nativo de Spring Boot) antes que introducir SDKs de terceros no gestionados.
- **Salida Estructurada:** Los nodos ejecutores de herramientas deben devolver resultados estructurados (JSON stringified) para facilitar su consumo por el siguiente agente en la cadena.
- Las herramientas deben ser:
  - deterministas (cuando aplique)
  - idempotentes (cuando sea posible)
  - observables (logs/metrics/traces)
- Validar input del modelo (no confiar en argumentos generados).

## Orquestación agéntica (equivalencias tipo LangGraph)

El repositorio debe soportar patrones agénticos robustos.

### Equivalencias recomendadas

- **ReAct / bucles simples (LangGraph simple loop):**
  - Usar bucles controlados con Spring AI (p.ej. advisors/llamadas recursivas) con:
    - límite de iteraciones
    - condiciones de parada
    - registro de cada “step”

- **Grafos cíclicos complejos (LangGraph StateGraph):**
  - **Opción A (Simple):** "Plain Java Loop".
    - Implementar el bucle de control en un Servicio (`while(shouldContinue)`).
    - Adecuado para flujos síncronos sin persistencia intermedia compleja.
  - **Opción B (Recomendada para Complejidad):** **Spring AI Alibaba Graph**.
    - Usar `StateGraph`, `Node`, `Edge` y `compiledGraph`.
    - Provee soporte nativo para **Graph State**, **interruptBefore** (HITL) y **Checkpointers**.
    - Es el equivalente directo a LangGraph en el ecosistema Java/Spring AI.
  - **Configuración:** Usar `@Configuration(proxyBeanMethods = false)` en las clases de configuración del Grafo para evitar errores de CGLIB/referencias circulares al inyectar Nodos.

- **Orquestación de larga duración + resiliencia (checkpoints / human-in-the-loop):**
  - **Spring AI Alibaba Graph** es la solución estándar para estos casos.
  - Usar `SaverConfig` con implementación de almacenamiento (JDBC/Redis) para persistir el `OverAllState`.
  - Configurar `interruptBefore` en nodos específicos para flujos de aprobación humana.
  - **Patrón de Reanudación (HITL):** Para continuar un flujo interrumpido con nuevo input (feedback):
    1. **Recuperar** estado: `graph.stateOf(config)`.
    2. **Actualizar** estado explícitamente: `RunnableConfig newConfig = graph.updateState(config, mapConNuevosMensajes)`. **CRÍTICO:** `updateState` devuelve una nueva configuración con el ID del nuevo checkpoint.
    3. **Reanudar**: `graph.invoke(mapConNuevosMensajes, newConfig)` (usar el input actualizado y la **nueva configuración** para asegurar que el contexto llegue íntegro al siguiente nodo).

### Patrones agénticos que deben poder implementarse

- **Chain workflow** (secuencial)
- **Parallelization** (Batching): Usar `CompletableFuture` para ejecutar múltiples llamadas a herramientas independientes en paralelo (p.ej. múltiples queries de búsqueda).
- **Routing** (clasificación de intención → seleccionar flujo/herramienta)
- **Orchestrator-Workers** (descomposición y delegación)
- **Reflexion (Evaluator-Optimizer):** Ciclos de retroalimentación donde un agente "Revisor" mejora la salida basándose en crítica y evidencias externas, usando un estado compartido tipado.
- **Separación Razonamiento/Acción:** Para implementar grafos donde un nodo decide y otro ejecuta:
  - Usar `ChatModel` (o `ChatClient`) configurado con `.internalToolExecutionEnabled(false)`.
  - Esto evita que el framework ejecute la herramienta automáticamente, permitiendo interceptar el `ToolCall` en el grafo y enrutarlo al nodo "Action".

## MCP (Model Context Protocol)

- Preferir MCP para exponer herramientas/datos como capacidades desacopladas.
- MCP Server (Spring Boot) debe:
  - definir contratos estables
  - tener auth cuando corresponda
  - exponer capacidades de forma clara
- MCP Client (agente) debe:
  - descubrir capacidades
  - no acoplarse a endpoints internos

## Observabilidad (mínimo)

- Registrar cada interacción IA con:
  - requestId/correlationId
  - modelo usado
  - latencia
  - resultado (ok/error)
- Evitar loguear secretos o PII.
- Si se añade Micrometer/Tracing, instrumentar:
  - llamadas al modelo
  - herramientas
  - retrieval

## Testing y calidad

- Agregar pruebas con JUnit 5.
- Para integración:
  - usar **Testcontainers** cuando haya dependencias reales (DB vectorial, etc.).
- Añadir tests “AI-aware” cuando el feature lo requiera:
  - evaluación de relevancia/correctitud con criterios explícitos (usar `RelevancyEvaluator` de Spring AI Evaluation).
  - tests parametrizados con fixtures
  - tolerancias (no tests frágiles por texto exacto)

## Seguridad y configuración

- Variables de entorno preferidas:
  - `SPRING_AI_OPENAI_API_KEY`
- No commitear `.env` ni claves.
- `application.properties` debe referenciar variables (placeholder) y tener valores por defecto seguros.

## Comandos estándar (copiar/pegar)

- Compilar:
  - `./mvnw -q -DskipTests package`
- Test:
  - `./mvnw -q test`
- Ejecutar:
  - `SPRING_AI_OPENAI_API_KEY=... ./mvnw -q spring-boot:run`

## Checklist antes de entregar

- El build pasa con `./mvnw test`.
- No se agregaron secretos al repo.
- Los flujos de IA tienen límites (iteraciones/timeouts) y manejo de errores.
- Las herramientas validan inputs del modelo.
- Si aplica RAG: existe ingesta/retrieval reproducible (y tests).
