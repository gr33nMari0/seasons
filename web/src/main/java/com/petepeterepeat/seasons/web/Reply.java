// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A response before it is sent: {@link WebServer} adds the security headers and the Cache-Control
 * every response carries.
 *
 * @param personal whether it depends on who asked, so it is never cached for anyone else
 */
record Reply(
    int status,
    String contentType,
    String body,
    Map<String, String> headers,
    List<String> cookies,
    boolean personal) {
  static final String JSON = "application/json; charset=utf-8";

  Reply {
    headers = Map.copyOf(headers);
    cookies = List.copyOf(cookies);
  }

  static Reply json(int status, JsonElement body) {
    return new Reply(status, JSON, body.toString(), Map.of(), List.of(), false);
  }

  static Reply ok(JsonElement body) {
    return json(200, body);
  }

  static Reply error(int status, String error) {
    JsonObject body = new JsonObject();
    body.addProperty("error", error);
    return json(status, body);
  }

  static Reply html(String text) {
    return new Reply(200, "text/html; charset=utf-8", text, Map.of(), List.of(), false);
  }

  Reply withHeader(String name, String value) {
    Map<String, String> next = new LinkedHashMap<>(headers);
    next.put(name, value);
    return new Reply(status, contentType, body, next, cookies, personal);
  }

  Reply withCookie(String line) {
    List<String> next = new ArrayList<>(cookies);
    next.add(line);
    return new Reply(status, contentType, body, headers, next, true);
  }

  Reply privately() {
    return new Reply(status, contentType, body, headers, cookies, true);
  }
}
