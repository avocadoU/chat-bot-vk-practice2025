package vk.chatbot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class Connection {
    protected static final String ACCESS_TOKEN = "vk1.a.VTs35iuWFmwtrm6FCpJ4TyvdCJLyGw3EW2_7APUobOSbbj7D69UnQMG65wwQkOZm5IhMs9H-j3ZbKeAjZFda3cljQHgYKk96JjECjv9F6jErHbBwU1D6Mrdu_zrqaShG2EI04ozJJPRPdQkiVMNbGCkVLF_pXlRd8TyKCBmoQ3FaAdNGdNAK21KCQxtshDwaGfnNn294TrbskVeX42TwkQ";
    protected static final int GROUP_ID = 231879059;
    protected static final String VK_API_VERSION = "5.199";

    protected static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(new HttpLoggingInterceptor(message -> System.out.println("NET: " + message)))
            .build();

    public static void startLongPoll() {
        // SiteAnalyzer.setup(); // Удалить или реализовать этот метод, если нужен

        while (true) {
            try {
                JsonObject serverData = getServerData();
                String server = serverData.get("server").getAsString();
                String key = serverData.get("key").getAsString();
                String ts = serverData.get("ts").getAsString();

                // Основной цикл Long Poll
                while (true) {
                    try {
                        String url = String.format("%s?act=a_check&key=%s&ts=%s&wait=25",
                                server, key, ts);

                        Request request = new Request.Builder()
                                .url(url)
                                .build();

                        try (Response response = client.newCall(request).execute()) {
                            if (!response.isSuccessful()) {
                                throw new IOException("HTTP error: " + response.code());
                            }

                            String json = response.body().string();
                            JsonObject update = JsonParser.parseString(json).getAsJsonObject();

                            // Обработка ошибок Long Poll
                            if (update.has("failed")) {
                                handleLongPollError(update.get("failed").getAsInt());
                                break;
                            }

                            // Обновление TS
                            ts = update.get("ts").getAsString();

                            // Обработка сообщений
                            if (update.has("updates")) {
                                for (JsonElement element : update.getAsJsonArray("updates")) {
                                    VkBot.processUpdate(element.getAsJsonObject());
                                }
                            }
                        }
                    } catch (IOException e) {
                        System.err.println("Ошибка соединения: " + e.getMessage());
                        sleep(5000);
                        break;
                    }
                }
            } catch (Exception e) {
                System.err.println("Критическая ошибка: " + e.getMessage());
                sleep(15000);
            }
        }
    }

    private static JsonObject getServerData() throws IOException {
        String url = String.format(
                "https://api.vk.com/method/groups.getLongPollServer?group_id=%d&access_token=%s&v=%s",
                GROUP_ID, ACCESS_TOKEN, VK_API_VERSION
        );

        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            String json = response.body().string();
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            if (obj.has("error")) {
                throw new IOException("API error: " +
                        obj.getAsJsonObject("error").get("error_msg").getAsString());
            }

            return obj.getAsJsonObject("response");
        }
    }

    private static void handleLongPollError(int errorCode) throws IOException {
        switch (errorCode) {
            case 1:
                System.out.println("Long Poll: Нужно обновить TS");
                break;
            case 2:
                throw new IOException("Long Poll: Нужен новый ключ");
            case 3:
                throw new IOException("Long Poll: Нужен новый сервер");
            default:
                throw new IOException("Long Poll: Неизвестная ошибка " + errorCode);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
