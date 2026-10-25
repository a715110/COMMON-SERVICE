package com.dodaso.ecosystem.common.service;

import com.dodaso.ecosystem.ecws.dto.SendResultDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ADDED 2026-10-23. Sends a Teams direct message as the signed-in person through Microsoft Graph
 * (delegated access token with Chat.Create and ChatMessage.Send). Three calls: who am I, create or
 * find the one to one chat with the recipient (Graph returns the existing chat when there is
 * one), post the message. The access token comes from the UI (Connect Microsoft account) and is
 * never stored here.
 */
@Component
@Slf4j
public class TeamsGraphClient {

  private static final String GRAPH = "https://graph.microsoft.com/v1.0";
  private static final String USER_BIND = "user@odata.bind";
  private static final String MEMBER_TYPE = "#microsoft.graph.aadUserConversationMember";

  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  /** Sends text to the recipient (user principal name or Microsoft user id). */
  public SendResultDTO send(final String accessToken, final String recipient, final String text) {
    try {
      final Reply me = call("GET", "/me?$select=id", accessToken, null);
      if (!me.ok()) {
        return failed("Could not read your Microsoft account", me);
      }
      final String myId = me.body().path("id").asText("");

      final Map<String, Object> chatRequest = Map.of(
          "chatType", "oneOnOne",
          "members", List.of(
              member(myId),
              member(recipient)));
      final Reply chat = call("POST", "/chats", accessToken, chatRequest);
      if (!chat.ok()) {
        return failed("Could not open a Teams chat with " + recipient, chat);
      }
      final String chatId = chat.body().path("id").asText("");
      if (chatId.isEmpty()) {
        return SendResultDTO.failure("Teams returned no chat", 502);
      }

      final Map<String, Object> message = Map.of(
          "body", Map.of("contentType", "html", "content", toHtml(text)));
      final Reply sent = call("POST", "/chats/" + chatId + "/messages", accessToken, message);
      if (!sent.ok()) {
        return failed("Teams did not accept the message", sent);
      }
      log.info("Teams message sent to {} in chat {}", recipient, chatId);
      return SendResultDTO.success("Message sent via Teams");
    } catch (final Exception e) {
      log.error("Error sending Teams message", e);
      return SendResultDTO.failure("Teams error: " + e.getMessage());
    }
  }

  private static Map<String, Object> member(final String userIdOrUpn) {
    return Map.of(
        "@odata.type", MEMBER_TYPE,
        "roles", List.of("owner"),
        USER_BIND, GRAPH + "/users('" + userIdOrUpn.replace("'", "''") + "')");
  }

  private SendResultDTO failed(final String what, final Reply reply) {
    final String detail = reply.body().path("error").path("message").asText("");
    final String code = reply.body().path("error").path("code").asText("");
    // REVISED 2026-10-24: a 401 is now logged with Microsoft's reason before asking for sign-in.
    if (reply.status() == 401) {
      log.warn("Graph call refused the token: {} HTTP 401 {} {}", what, code, detail);
      return SendResultDTO.needsLogin("Microsoft sign-in required");
    }
    log.warn("Graph call failed: {} HTTP {} {} {}", what, reply.status(), code, detail);
    final String hint = switch (reply.status()) {
      case 403 -> " (permission refused: check admin consent for Chat.Create and ChatMessage.Send,"
          + " and that both people have a Teams licence)";
      case 404 -> " (recipient not found in Microsoft 365)";
      default -> "";
    };
    return SendResultDTO.failure(
        what + hint + (detail.isEmpty() ? "" : ": " + detail), reply.status());
  }

  private Reply call(final String method, final String path, final String token,
      final Object body) throws Exception {
    final HttpRequest.Builder request = HttpRequest.newBuilder()
        .uri(URI.create(GRAPH + path))
        .timeout(Duration.ofSeconds(20))
        .header("Authorization", "Bearer " + token)
        .header("Accept", "application/json");
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request.header("Content-Type", "application/json");
      request.method(method, HttpRequest.BodyPublishers.ofString(
          mapper.writeValueAsString(body), StandardCharsets.UTF_8));
    }
    final HttpResponse<String> response =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    JsonNode json;
    try {
      json = response.body() == null || response.body().isBlank()
          ? mapper.createObjectNode() : mapper.readTree(response.body());
    } catch (final Exception e) {
      json = mapper.createObjectNode();
    }
    return new Reply(response.statusCode(), json);
  }

  /** Plain text to Teams HTML: escaped, line breaks kept, web addresses made clickable. */
  static String toHtml(final String text) {
    if (text == null) {
      return "";
    }
    final String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\r", "");
    return escaped
        .replaceAll("(https?://[^\\s<]+)", "<a href=\"$1\">$1</a>")
        .replace("\n", "<br>");
  }

  private record Reply(int status, JsonNode body) {

    boolean ok() {
      return status / 100 == 2;
    }
  }
}
