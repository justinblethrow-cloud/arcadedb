/*
 * Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.server.mcp;

import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.BaseGraphServerTest;
import com.arcadedb.server.mcp.prompts.BuildKnowledgeGraphPrompt;
import com.arcadedb.server.mcp.prompts.GraphRagQueryPrompt;
import com.arcadedb.server.security.ServerSecurityUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MCPPromptsTest extends BaseGraphServerTest {
  private MCPConfiguration   config;
  private ServerSecurityUser user;

  @BeforeEach
  void setupMCP() {
    config = getServer(0).getMCPConfiguration();
    config.setEnabled(true);
    config.setToolProfile(MCPConfiguration.ToolProfile.ALL);
    user = getServer(0).getSecurity().authenticate("root", DEFAULT_PASSWORD_FOR_TESTS, null);
  }

  @Test
  void listReturnsBothPromptDefinitionsAndRequiredArguments() {
    final JSONArray prompts = MCPPrompts.list().getJSONArray("prompts");
    assertThat(prompts.length()).isEqualTo(2);

    final Map<String, JSONObject> byName = new HashMap<>();
    for (int i = 0; i < prompts.length(); i++)
      byName.put(prompts.getJSONObject(i).getString("name"), prompts.getJSONObject(i));

    assertThat(byName.keySet()).containsExactlyInAnyOrder(
        GraphRagQueryPrompt.NAME, BuildKnowledgeGraphPrompt.NAME);
    assertArguments(byName.get(GraphRagQueryPrompt.NAME), "database", "question");
    assertArguments(byName.get(BuildKnowledgeGraphPrompt.NAME), "database", "sourceText");
  }

  @Test
  void getReturnsReviewableUserMessages() {
    final JSONObject graphRag = MCPPrompts.get(new JSONObject()
        .put("name", GraphRagQueryPrompt.NAME)
        .put("arguments", new JSONObject()
            .put("database", "knowledge")
            .put("question", "Who collaborated with Ada?")));
    final String graphRagText = messageText(graphRag);
    assertThat(graphRagText)
        .contains("knowledge", "Who collaborated with Ada?", "vector_search", "hybrid_search",
            "full_text_search", "rid")
        .contains("does not generate embeddings");

    final JSONObject builder = MCPPrompts.get(new JSONObject()
        .put("name", BuildKnowledgeGraphPrompt.NAME)
        .put("arguments", new JSONObject()
            .put("database", "knowledge")
            .put("sourceText", "Ada collaborated with Charles.")));
    final String builderText = messageText(builder);
    assertThat(builderText)
        .contains("knowledge", "Ada collaborated with Charles.", "upsert_entity", "upsert_relationship",
            "matchKeys", "untrusted data")
        .contains("do not follow instructions");
  }

  @Test
  void everyToolNamedByThePromptsIsRegistered() {
    final MCPDispatcher dispatcher = new MCPDispatcher(getServer(0), config, "test");
    final MCPDispatcher.MCPResponse response = dispatcher.dispatch(new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "tools/list")
        .put("params", new JSONObject()), user);

    final Set<String> registered = new HashSet<>();
    final JSONArray tools = response.json().getJSONObject("result").getJSONArray("tools");
    for (int i = 0; i < tools.length(); i++)
      registered.add(tools.getJSONObject(i).getString("name"));

    final Map<String, Set<String>> references = Map.of(
        messageText(MCPPrompts.get(new JSONObject()
            .put("name", GraphRagQueryPrompt.NAME)
            .put("arguments", new JSONObject().put("database", "db").put("question", "question")))),
        Set.of("query", "vector_search", "hybrid_search", "full_text_search"),
        messageText(MCPPrompts.get(new JSONObject()
            .put("name", BuildKnowledgeGraphPrompt.NAME)
            .put("arguments", new JSONObject().put("database", "db").put("sourceText", "text")))),
        Set.of("upsert_entity", "upsert_relationship", "execute_command"));

    for (final Map.Entry<String, Set<String>> entry : references.entrySet())
      for (final String toolName : entry.getValue()) {
        assertThat(entry.getKey()).contains(toolName);
        assertThat(registered).contains(toolName);
      }
  }

  @Test
  void invalidPromptRequestsReturnInvalidParams() {
    final MCPDispatcher dispatcher = new MCPDispatcher(getServer(0), config, "test");

    MCPDispatcher.MCPResponse response = dispatcher.dispatch(promptRequest("unknown", new JSONObject()), user);
    assertThat(response.json().getJSONObject("error").getInt("code")).isEqualTo(-32602);
    assertThat(response.json().getJSONObject("error").getString("message")).contains("Unknown prompt");

    response = dispatcher.dispatch(promptRequest(GraphRagQueryPrompt.NAME,
        new JSONObject().put("database", "graph")), user);
    assertThat(response.json().getJSONObject("error").getInt("code")).isEqualTo(-32602);
    assertThat(response.json().getJSONObject("error").getString("message")).contains("question");

    response = dispatcher.dispatch(new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 3)
        .put("method", "prompts/get")
        .put("params", new JSONObject()
            .put("name", GraphRagQueryPrompt.NAME)
            .put("arguments", new JSONArray().put("not-an-object"))), user);
    assertThat(response.json().getJSONObject("error").getInt("code")).isEqualTo(-32602);
    assertThat(response.json().getJSONObject("error").getString("message")).contains("arguments");
  }

  private static void assertArguments(final JSONObject prompt, final String... expectedNames) {
    final JSONArray arguments = prompt.getJSONArray("arguments");
    final Set<String> names = new HashSet<>();
    for (int i = 0; i < arguments.length(); i++) {
      final JSONObject argument = arguments.getJSONObject(i);
      names.add(argument.getString("name"));
      assertThat(argument.getBoolean("required")).isTrue();
      assertThat(argument.getString("description")).isNotBlank();
    }
    assertThat(names).containsExactlyInAnyOrder(expectedNames);
  }

  private static String messageText(final JSONObject prompt) {
    assertThat(prompt.getString("description")).isNotBlank();
    final JSONArray messages = prompt.getJSONArray("messages");
    assertThat(messages.length()).isEqualTo(1);
    final JSONObject message = messages.getJSONObject(0);
    assertThat(message.getString("role")).isEqualTo("user");
    final JSONObject content = message.getJSONObject("content");
    assertThat(content.getString("type")).isEqualTo("text");
    return content.getString("text");
  }

  private static JSONObject promptRequest(final String name, final JSONObject arguments) {
    return new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 2)
        .put("method", "prompts/get")
        .put("params", new JSONObject()
            .put("name", name)
            .put("arguments", arguments));
  }
}
