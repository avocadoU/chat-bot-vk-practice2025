package vk.chatbot;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

public class SiteAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(SiteAnalyzer.class);
    private static final Set<String> parsedUrls = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static int currentPageNumber = 1;
    private static final int MAX_PAGES = 10;
    private static final int MAX_RETRIES = 3;

    public static List<ProjectInfo> parseAllProjects() {
        List<ProjectInfo> projects = new ArrayList<>();
        WebDriver driver = WebDriverProvider.getDriver();
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

        try {
            initializeParsingSession(driver, wait);

            while (currentPageNumber <= MAX_PAGES) {
                if (!parseCurrentPageWithRetry(driver, projects, MAX_RETRIES)) {
                    break;
                }

                if (!navigateToNextPage(driver)) {
                    break;
                }

                currentPageNumber++;
                waitForPageLoad(driver);
            }

            logger.info("Парсинг завершен. Всего найдено уникальных проектов: {}", projects.size());
        } catch (Exception e) {
            logger.error("Критическая ошибка парсинга: ", e);
        } finally {
            driver.switchTo().defaultContent();
        }
        return projects;
    }

    private static void initializeParsingSession(WebDriver driver, WebDriverWait wait) {
        parsedUrls.clear();
        currentPageNumber = 1;

        driver.get("https://education.vk.company/education_projects");
        wait.until(ExpectedConditions.jsReturnsValue("return document.readyState === 'complete'"));

        WebElement iframe = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("iframe.education_projects__Iframe-sc-1aobee9-0")));
        driver.switchTo().frame(iframe);

        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector(".t-store__card__wrap_all")));
    }

    private static boolean parseCurrentPageWithRetry(WebDriver driver, List<ProjectInfo> projects, int maxRetries) {
        int attempts = 0;
        while (attempts < maxRetries) {
            attempts++;
            int beforeCount = projects.size();

            parseCurrentPage(driver, projects);
            int newProjects = projects.size() - beforeCount;

            if (newProjects > 0) {
                return true;
            }

            logger.warn("Попытка {}/{}: на странице {} не найдено новых проектов",
                    attempts, maxRetries, currentPageNumber);

            if (attempts < maxRetries) {
                refreshPage(driver);
            }
        }
        return false;
    }

    private static void parseCurrentPage(WebDriver driver, List<ProjectInfo> projects) {
        List<WebElement> cards = driver.findElements(By.cssSelector(
                ".t-store__card__wrap_all, .js-product.t-store_card"));

        StringBuilder projectTitles = new StringBuilder();
        int newProjectsCount = 0;

        for (WebElement card : cards) {
            try {
                ProjectInfo project = extractProjectInfo(card);
                if (project != null && !parsedUrls.contains(project.getUrl())) {
                    projects.add(project);
                    parsedUrls.add(project.getUrl());
                    projectTitles.append(project.getTitle()).append(" | ");
                    newProjectsCount++;
                }
            } catch (Exception e) {
                logger.warn("Ошибка обработки карточки проекта: {}", e.getMessage());
            }
        }

        if (newProjectsCount > 0) {
            String titlesPreview = projectTitles.length() > 200
                    ? projectTitles.substring(0, 200) + "..."
                    : projectTitles.toString();

            logger.info("Страница {}: {}\nНайдено проектов: {}",
                    currentPageNumber, titlesPreview, newProjectsCount);
        }
    }

    private static ProjectInfo extractProjectInfo(WebElement card) throws NoSuchElementException {
        WebElement link = card.findElement(By.cssSelector("a"));
        String url = link.getAttribute("href");

        if (url == null || url.isEmpty()) {
            return null;
        }

        String title = card.findElement(By.cssSelector(
                ".js-store-prod-name, .js-product-name, .t-store__card__title")).getText();
        String description = card.findElement(By.cssSelector(
                ".js-store-prod-descr, .js-product-descr, .t-store__card__descr")).getText();

        return new ProjectInfo(title, description, url);
    }

    private static boolean navigateToNextPage(WebDriver driver) {
        try {
            // Проверяем наличие элементов пагинации вообще
            List<WebElement> paginationElements = driver.findElements(By.cssSelector(
                    ".t-store__pagination__item_page, .t-store__pagination__btn_next"));

            if (paginationElements.isEmpty()) {
                logger.debug("Элементы пагинации не найдены");
                return false;
            }

            // Сначала пробуем найти кнопку следующей страницы по номеру
            String nextPageNumSelector = String.format(
                    ".t-store__pagination__item_page[data-page-num='%d']",
                    currentPageNumber + 1);

            List<WebElement> nextPageButtons = driver.findElements(By.cssSelector(nextPageNumSelector));

            // Если не нашли, пробуем кнопку "Далее"
            if (nextPageButtons.isEmpty()) {
                nextPageButtons = driver.findElements(
                        By.cssSelector(".t-store__pagination__btn_next:not(.t-disabled)"));

                if (nextPageButtons.isEmpty()) {
                    logger.info("Достигнут конец страниц. Нет активной кнопки перехода");
                    return false;
                }
            }

            WebElement nextButton = nextPageButtons.get(0);
            if (!nextButton.isDisplayed() || !nextButton.isEnabled()) {
                logger.debug("Кнопка перехода неактивна или невидима");
                return false;
            }

            scrollAndClick(driver, nextButton);

            // Ждем загрузки нового контента
            waitForPageContentLoad(driver);
            return true;

        } catch (NoSuchElementException e) {
            logger.info("Элементы пагинации не найдены. Парсинг завершен");
            return false;
        } catch (Exception e) {
            logger.warn("Ошибка при попытке перехода на следующую страницу: {}", e.getMessage());
            return false;
        }
    }

    private static void waitForPageContentLoad(WebDriver driver) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(15))
                    .until(d -> {
                        try {
                            return ((JavascriptExecutor)d).executeScript(
                                    "return document.readyState === 'complete' && " +
                                            "document.querySelectorAll('.t-store__card__wrap_all').length > 0");
                        } catch (Exception e) {
                            return false;
                        }
                    });
        } catch (Exception e) {
            logger.warn("Контент страницы не загрузился полностью: {}", e.getMessage());
        }
    }

    private static void scrollAndClick(WebDriver driver, WebElement element) {
        ((JavascriptExecutor)driver).executeScript(
                "arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});",
                element);
        try {
            Thread.sleep(1000);
            ((JavascriptExecutor)driver).executeScript("arguments[0].click();", element);
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при клике на элемент", e);
        }
    }

    private static void refreshPage(WebDriver driver) {
        try {
            driver.navigate().refresh();
            Thread.sleep(2000);
            new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector(".t-store__card__wrap_all")));
        } catch (Exception e) {
            logger.warn("Ошибка при обновлении страницы: {}", e.getMessage());
        }
    }

    private static void waitForPageLoad(WebDriver driver) {
        try {
            Thread.sleep(2000);
            new WebDriverWait(driver, Duration.ofSeconds(15))
                    .until(ExpectedConditions.jsReturnsValue(
                            "return document.readyState === 'complete'"));
        } catch (Exception e) {
            logger.warn("Ошибка ожидания загрузки страницы: {}", e.getMessage());
        }
    }
}