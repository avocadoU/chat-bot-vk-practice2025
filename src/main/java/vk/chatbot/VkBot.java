package vk.chatbot;

import okhttp3.*;
import okhttp3.logging.HttpLoggingInterceptor;
import com.google.gson.*;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class VkBot {
    private static final OkHttpClient client = Connection.client;
    private static final String ACCESS_TOKEN = Connection.ACCESS_TOKEN;
    private static final int GROUP_ID = Connection.GROUP_ID;
    private static final String VK_API_VERSION = Connection.VK_API_VERSION;

    public static void startLongPoll() {
        System.out.println("\u001B[32m╔════════════════════════════╗");
        System.out.println("\u001B[32m║   VK Bot успешно запущен!  ║");
        System.out.println("\u001B[32m╚════════════════════════════╝\u001B[0m");

        Connection.startLongPoll();
    }

    protected static void processUpdate(JsonObject update) {
        if (!"message_new".equals(update.get("type").getAsString())) return;

        JsonObject msg = update.getAsJsonObject("object").getAsJsonObject("message");
        int peerId = msg.get("peer_id").getAsInt();
        String text = msg.get("text").getAsString().toLowerCase().trim();

        String senderInfo = getSenderName(peerId);
        System.out.println("\u001B[33mНовое сообщение от " + senderInfo + ": " + text);

        if (text.equalsIgnoreCase("Начать") || text.equalsIgnoreCase("/start")) {
            sendWelcomeMessage(peerId);
        } else if (text.equalsIgnoreCase("проекты")) {
            return;
        } else {
            sendMessage(peerId, "⚠️ Неизвестная команда");
        }

    }

    private static void sendMessage(int peerId, String text) {
        try {
            String url = String.format(
                    "https://api.vk.com/method/messages.send?peer_id=%d&message=%s&random_id=%d&access_token=%s&v=%s",
                    peerId, text, (int)(Math.random()*10000), ACCESS_TOKEN, VK_API_VERSION
            );

            Request request = new Request.Builder().url(url).build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("Ошибка отправки: " + response.code());
                }
            }
        } catch (IOException e) {
            System.err.println("Ошибка сети при отправке: " + e.getMessage());
        }
    }

    private static void sendWelcomeMessage(int peerId) {
        String msg = String.format("Привет, %s!<br>Я помогу тебе разобраться с VK Education Projects \uD83C\uDF1F<br>" +
                        "Здесь ты найдешь ответы на свои вопросы",
                getSenderName(peerId));
        sendMessage(peerId, msg);

        Connection.sleep(600);

        msg = "VK Education Projects — витрина проектов для студентов. " +
              "Проекты могут быть использованы для выполнения домашних заданий, научно-исследовательских, курсовых и дипломных работ";
        sendMessage(peerId, msg);
    }

    private static String getSenderName(int userId) {
        try {
            String url = String.format(
                    "https://api.vk.com/method/users.get?user_ids=%d&fields=first_name,last_name&access_token=%s&v=%s",
                    userId, ACCESS_TOKEN, VK_API_VERSION
            );

            Request request = new Request.Builder().url(url).build();
            try (Response response = client.newCall(request).execute()) {
                JsonObject obj = JsonParser.parseString(response.body().string()).getAsJsonObject();
                JsonObject user = obj.getAsJsonArray("response").get(0).getAsJsonObject();
                return user.get("first_name").getAsString();
            }
        } catch (Exception e) {
            return "Пользователь";
        }
    }
}