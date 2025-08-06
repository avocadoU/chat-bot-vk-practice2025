package vk.chatbot;

import com.google.gson.*;
import io.github.bonigarcia.wdm.WebDriverManager;
import okhttp3.*;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.TimeoutException; // Явное указание нужного TimeoutException

public class VkBot {
    private static final Logger logger = LoggerFactory.getLogger(VkBot.class);
    private static final OkHttpClient client = Connection.client;
    private static final String ACCESS_TOKEN = Connection.ACCESS_TOKEN;
    private static final String VK_API_VERSION = Connection.VK_API_VERSION;
    private static final ExecutorService executor = Executors.newFixedThreadPool(3);
    private static final int SELENIUM_TIMEOUT_SEC = 30;
    private static final int MESSAGE_LIMIT = 4096;
    private static final String SCREENSHOT_DIR = "screenshots";

    private static List<ProjectInfo> allProjects = new ArrayList<>();

    public static void startLongPoll() {
        logger.info("=== Запуск VK бота ===");
        WebDriver driver = WebDriverProvider.getDriver();

        // Парсим проекты один раз при запуске
        allProjects = SiteAnalyzer.parseAllProjects();
        logger.info("Найдено проектов: {}", allProjects.size());

        if (!testSeleniumConnection(driver)) {
            logger.error("Ошибка подключения Selenium");
            closeResources();
            return;
        }

        try {
            Files.createDirectories(Paths.get(SCREENSHOT_DIR));
            logger.info("Директория для скриншотов создана: {}", SCREENSHOT_DIR);
        } catch (IOException e) {
            logger.warn("Не удалось создать директорию для скриншотов", e);
        }

        logger.info("Бот готов к работе");
        Connection.startLongPoll();
    }

    protected static void processUpdate(JsonObject update) {
        try {
            if (!"message_new".equals(update.get("type").getAsString())) return;

            JsonObject msg = update.getAsJsonObject("object").getAsJsonObject("message");
            int peerId = msg.get("peer_id").getAsInt();
            String text = msg.get("text").getAsString().trim();

            logger.info("Получено сообщение от {}: {}", peerId, text);
            if (text.equalsIgnoreCase("начать")) {
                sendWelcomeMessage(peerId);
            } else if (text.toLowerCase().startsWith("найди")) {
                processSearchRequest(peerId, text);
            } else if (isYesNoQuestion(text)) {
                sendMessage(peerId, answerYesNo(text));
            } else {
                sendHelpMessage(peerId);
            }

            if (containsBadWords(text)) {
                sendMessage(peerId, "⚠️ Ваше сообщение содержит некорректные выражения.");
                return;
            }
        } catch (Exception e) {
            logger.error("Ошибка обработки сообщения", e);
        }
    }

    private static boolean isYesNoQuestion(String text) {
        String lower = text.toLowerCase();
        return lower.startsWith("можно ли") || lower.startsWith("возможно ли") || lower.startsWith("есть ли");
    }

    private static String answerYesNo(String text) {
        // Можно добавить простую логику или всегда отвечать "Да."
        return "Да.";
    }

    private static String generateAnswer(String query) {
        String lowerQuery = query.toLowerCase();
        for (ProjectInfo project : allProjects) {
            if (project.getTitle().toLowerCase().contains(lowerQuery) ||
                project.getDescription().toLowerCase().contains(lowerQuery)) {
                return String.format("Нашёл проект:\n%s\n%s\n%s", project.getTitle(), project.getDescription(), project.getUrl());
            }
        }
        return "Я не нашёл точного ответа на ваш вопрос. Попробуйте задать его иначе или воспользуйтесь поиском на сайте: https://education.vk.company/education_projects";
    }

    private static void processSearchRequest(int peerId, String text) {
        executor.submit(() -> {
            String query = text.substring(5).trim();
            if (query.isEmpty()) {
                sendMessage(peerId, "Пожалуйста, укажите поисковый запрос после команды \"найди\"");
                return;
            }
            String result = generateAnswer(query);
            sendMessage(peerId, result);
        });
    }

    // Остальные методы остаются без изменений
    private static String searchOnSite(String query) {
        WebDriver driver = null;
        try {
            WebDriverManager.chromedriver().driverVersion("138.0.7204.184").setup();
            ChromeOptions options = new ChromeOptions();
            // Не используем headless!
            options.addArguments("--headless", "--disable-gpu", "--no-sandbox", "--disable-dev-shm-usage");
            options.addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36");
            driver = new ChromeDriver(options);

            driver.get("https://education.vk.company/education_projects");
            Thread.sleep(1500); // Дать время на загрузку

            // Прокрутка вниз для появления поиска
            ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, document.body.scrollHeight);");
            Thread.sleep(1500);

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
            WebElement searchInput = wait.until(
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector("input[type='search']"))
            );
            searchInput.clear();
            searchInput.sendKeys(query);
            searchInput.sendKeys(Keys.ENTER);

            Thread.sleep(2000);

            java.util.List<WebElement> titles = driver.findElements(By.cssSelector(".education-projects__item-title"));
            if (titles.isEmpty()) {
                return "Ничего не найдено.";
            }
            StringBuilder sb = new StringBuilder();
            for (WebElement title : titles) {
                sb.append("• ").append(title.getText()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Ошибка поиска: " + e.getMessage();
        } finally {
            if (driver != null) driver.quit();
        }
    }

    private static void sendWelcomeMessage(int peerId) {
        String msg = String.format(
                "Привет, %s! Я бот по поиску информации на VK Education Projects",
                getSenderName(peerId));
        sendMessage(peerId, msg);

        sendHelpMessage(peerId);
    }

    private static void sendMessage(int peerId, String text) {
        try {
            if (text.length() > MESSAGE_LIMIT) {
                text = text.substring(0, MESSAGE_LIMIT - 3) + "...";
            }

            String url = String.format(
                    "https://api.vk.com/method/messages.send?peer_id=%d&message=%s&random_id=%d&access_token=%s&v=%s",
                    peerId,
                    URLEncoder.encode(text, "UTF-8"),
                    ThreadLocalRandom.current().nextInt(10000),
                    ACCESS_TOKEN,
                    VK_API_VERSION
            );

            Request request = new Request.Builder()
                    .url(url)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.error("Ошибка отправки сообщения: HTTP {}", response.code());
                }
            }
        } catch (Exception e) {
            logger.error("Ошибка отправки сообщения", e);
        }
    }

    private static String getSenderName(int userId) {
        try {
            String url = String.format(
                    "https://api.vk.com/method/users.get?user_ids=%d&fields=first_name,last_name&access_token=%s&v=%s",
                    userId, ACCESS_TOKEN, VK_API_VERSION
            );

            Request request = new Request.Builder().url(url).build();

            try (Response response = client.newCall(request).execute()) {
                JsonObject json = JsonParser.parseString(response.body().string()).getAsJsonObject();
                if (json.has("error")) {
                    throw new RuntimeException(json.getAsJsonObject("error").get("error_msg").getAsString());
                }
                JsonArray users = json.getAsJsonArray("response");
                if (users.size() > 0) {
                    JsonObject user = users.get(0).getAsJsonObject();
                    return user.get("first_name").getAsString();
                }
            }
        } catch (Exception e) {
            logger.error("Ошибка получения имени пользователя", e);
        }
        return "Друг";
    }

    private static void takeScreenshot(String filename, WebDriver driver) {
        try {
            byte[] screenshot = ((TakesScreenshot)driver).getScreenshotAs(OutputType.BYTES);
            Files.write(Paths.get(SCREENSHOT_DIR, filename), screenshot);
        } catch (Exception e) {
            logger.error("Ошибка создания скриншота", e);
        }
    }

    private static void closeResources() {
        try {
            WebDriverProvider.quitDriver();
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            logger.info("Пул потоков успешно закрыт");
        } catch (Exception e) {
            logger.error("Ошибка при завершении работы", e);
        }
    }

    private static String cleanText(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String formatSearchResults(String query, String results) {
        return String.format("🔍 Результаты по запросу \"%s\":\n%s",
                query,
                results.substring(0, Math.min(MESSAGE_LIMIT - 100, results.length()))
        );
    }

    private static String getShortErrorMessage(Exception e) {
        String msg = e.getMessage();
        return msg.substring(0, Math.min(200, msg.length()));
    }

    private static void sendHelpMessage(int peerId) {
        String helpText =
                "Доступные команды:\n" +
                        "• \"начать\" - приветственное сообщение\n" +
                        "• \"найди [запрос]\" - поиск информации\n\n" +
                        "Пример: \"найди курсы по Java\"";
        sendMessage(peerId, helpText);
    }

    private static boolean testSeleniumConnection(WebDriver driver) {
        try {
            driver.get("https://www.google.com");
            new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(ExpectedConditions.titleContains("Google"));
            return true;
        } catch (Exception e) {
            logger.error("Ошибка тестирования подключения Selenium", e);
            return false;
        }
    }

    private static boolean containsBadWords(String text) {
        String[] badWords = {"плохое_слово1", "плохое_слово2"}; // Заполните список
        String lower = text.toLowerCase();
        for (String word : badWords) {
            if (lower.contains(word)) return true;
        }
        return false;
    }
}