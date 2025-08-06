package vk.chatbot;

public class ProjectInfo {
    private final String title;
    private final String description;
    private final String url;

    public ProjectInfo(String title, String description, String url) {
        this.title = title;
        this.description = description;
        this.url = url;
    }

    // Геттеры
    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getUrl() {
        return url;
    }
}