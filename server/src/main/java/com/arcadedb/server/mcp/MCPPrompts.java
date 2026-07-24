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
import com.arcadedb.server.mcp.prompts.BuildKnowledgeGraphPrompt;
import com.arcadedb.server.mcp.prompts.GraphRagQueryPrompt;

/**
 * Transport-neutral registry for the Model Context Protocol prompt primitive.
 */
public class MCPPrompts {
  private static final JSONArray PROMPTS;

  static {
    PROMPTS = new JSONArray()
        .put(GraphRagQueryPrompt.getDefinition())
        .put(BuildKnowledgeGraphPrompt.getDefinition());
  }

  private MCPPrompts() {
  }

  public static JSONObject list() {
    return new JSONObject().put("prompts", PROMPTS);
  }

  public static JSONObject get(final JSONObject params) {
    final String name = requireString(params, "name");
    final Object rawArguments = params.opt("arguments");
    if (rawArguments != null && !(rawArguments instanceof JSONObject))
      throw new IllegalArgumentException("'arguments' must be an object");
    final JSONObject arguments = rawArguments instanceof JSONObject object ? object : new JSONObject();

    return switch (name) {
      case GraphRagQueryPrompt.NAME -> result(
          GraphRagQueryPrompt.getDescription(), GraphRagQueryPrompt.getMessages(arguments));
      case BuildKnowledgeGraphPrompt.NAME -> result(
          BuildKnowledgeGraphPrompt.getDescription(), BuildKnowledgeGraphPrompt.getMessages(arguments));
      default -> throw new IllegalArgumentException(
          "Unknown prompt '" + name + "'. Available prompts: "
              + GraphRagQueryPrompt.NAME + ", " + BuildKnowledgeGraphPrompt.NAME);
    };
  }

  private static JSONObject result(final String description, final JSONArray messages) {
    return new JSONObject()
        .put("description", description)
        .put("messages", messages);
  }

  private static String requireString(final JSONObject params, final String name) {
    final Object value = params.opt(name);
    if (!(value instanceof final String text) || text.isBlank())
      throw new IllegalArgumentException("'" + name + "' is required and must be a non-blank string");
    return text;
  }
}
