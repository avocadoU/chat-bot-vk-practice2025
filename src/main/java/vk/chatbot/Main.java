package vk.chatbot;

public class Main {
    public static void main(String[] args) {
        try {
            VkBot.startLongPoll();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}