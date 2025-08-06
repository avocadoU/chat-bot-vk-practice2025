package vk.chatbot;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

public class SiteAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(SiteAnalyzer.class);
    private static final Set<String> parsedUrls = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static int currentPageNumber = 1;

    public static List<ProjectInfo> parseAllProjects() {
        List<ProjectInfo> projects = new ArrayList<>();
        WebDriver driver = WebDriverProvider.getDriver();
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

        try {
            parsedUrls.clear();
            currentPageNumber = 1;

            driver.get("https://education.vk.company/education_projects");
            WebElement iframe = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("iframe.education_projects__Iframe-sc-1aobee9-0")));
            driver.switchTo().frame(iframe);

            // Сначала парсим первую страницу
            parseCurrentPage(driver, projects);

            // Затем обрабатываем последующие страницы
            while (currentPageNumber <= 10) { // Лимит 10 страниц
                if (!goToNextPage(driver)) {
                    break;
                }

                if (!parsePageWithRetry(driver, projects, 3)) {
                    break;
                }

                currentPageNumber++;
                Thread.sleep(2000);
            }

            driver.switchTo().defaultContent();
            logger.info("Всего найдено уникальных проектов: {}", projects.size());
        } catch (Exception e) {
            logger.error("Критическая ошибка парсинга: ", e);
        }
        return projects;
    }

    private static boolean parsePageWithRetry(WebDriver driver, List<ProjectInfo> projects, int maxAttempts) {
        int attempts = 0;
        while (attempts < maxAttempts) {
            attempts++;
            int beforeSize = projects.size();

            parseCurrentPage(driver, projects);
            boolean hasNew = projects.size() > beforeSize;

            if (hasNew) {
                logger.info("Страница {}: добавлено {} проектов",
                        currentPageNumber, projects.size() - beforeSize);
                return true;
            }

            logger.warn("Попытка {}/{}: новых проектов не найдено", attempts, maxAttempts);
            try {
                Thread.sleep(2000);
                driver.navigate().refresh();
                Thread.sleep(2000);
            } catch (Exception e) {
                logger.warn("Ошибка при обновлении страницы: {}", e.getMessage());
            }
        }
        return false;
    }

    private static void parseCurrentPage(WebDriver driver, List<ProjectInfo> projects) {
        List<WebElement> cards = driver.findElements(By.cssSelector(".t-store__card__wrap_all"));
        StringBuilder titles = new StringBuilder();
        int newProjects = 0;

        for (WebElement card : cards) {
            try {
                String url = card.findElement(By.cssSelector("a")).getAttribute("href");

                if (url != null && !parsedUrls.contains(url)) {
                    String title = card.findElement(By.cssSelector(".js-store-prod-name")).getText();
                    String desc = card.findElement(By.cssSelector(".js-store-prod-descr")).getText();

                    projects.add(new ProjectInfo(title, desc, url));
                    parsedUrls.add(url);
                    titles.append(title).append(" | ");
                    newProjects++;
                }
            } catch (Exception e) {
                logger.warn("Ошибка парсинга карточки: {}", e.getMessage());
            }
        }

        if (newProjects > 0) {
            logger.info("Страница {}: {}\nДобавлено: {} проектов",
                    currentPageNumber,
                    titles.substring(0, Math.min(titles.length(), 200)),
                    newProjects);
        } else {
            logger.info("Страница {}: новых проектов не найдено", currentPageNumber);
        }
    }

    private static boolean goToNextPage(WebDriver driver) {
        try {
            // 1. Пробуем найти следующую страницу по номеру
            List<WebElement> nextPageButtons = driver.findElements(
                    By.cssSelector(String.format(
                            ".t-store__pagination__item_page[data-page-num='%d']",
                            currentPageNumber + 1)));

            // 2. Если не нашли, пробуем кнопку "Далее"
            if (nextPageButtons.isEmpty()) {
                nextPageButtons = driver.findElements(
                        By.cssSelector(".t-store__pagination__btn_next:not(.t-disabled)"));
            }

            if (!nextPageButtons.isEmpty()) {
                WebElement button = nextPageButtons.get(0);
                ((JavascriptExecutor)driver).executeScript(
                        "arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});",
                        button);
                Thread.sleep(1000);

                if (button.isDisplayed() && button.isEnabled()) {
                    ((JavascriptExecutor)driver).executeScript("arguments[0].click();", button);

                    // Ждем, пока текущая страница не станет активной
                    new WebDriverWait(driver, Duration.ofSeconds(20))
                            .until(ExpectedConditions.numberOfElementsToBe(
                                    By.cssSelector(String.format(
                                            ".t-store__pagination__item_active[data-page-num='%d']",
                                            currentPageNumber + 1)), 1));

                    return true;
                }
            }
        } catch (Exception e) {
            logger.error("Ошибка перехода на страницу {}: {}", currentPageNumber + 1, e.getMessage());
        }
        return false;
    }
}