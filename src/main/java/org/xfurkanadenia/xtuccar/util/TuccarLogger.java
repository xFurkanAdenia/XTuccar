package org.xfurkanadenia.xtuccar.util;

import org.xfurkanadenia.xtuccar.Logger;
import org.xfurkanadenia.xtuccar.XTuccar;
import org.xfurkanadenia.xtuccar.model.MarketSellingItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TuccarLogger {

    private final XTuccar main;
    private final Path logDirectory;
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    public TuccarLogger(XTuccar main){
        this.main = main;
        this.logDirectory = main.getDataFolder().toPath().resolve("logs");
        createLogDirectory();
    }

    private void createLogDirectory() {
        try {
            if (!Files.exists(logDirectory)) {
                Files.createDirectories(logDirectory);
            }
        } catch (IOException e) {
            Logger.error("Failed to create log directory: " + e.getMessage());
        }
    }

    private Path getLogFile() {
        String fileName = "tuccar-" + LocalDateTime.now().format(dateFormatter) + ".log";
        return logDirectory.resolve(fileName);
    }

    private void log(String message) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        String logLine = "[" + timestamp + "] " + message;

        try {
            Path logFile = getLogFile();
            Files.writeString(logFile, logLine + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            Logger.error("Failed to write to log file: " + e.getMessage());
        }
    }

    public void logOrderDelivery(MarketSellingItem marketSelling, String delivererName, int deliveredAmount) {
        String message = String.format(
                "[TUCCAR_DELIVERY] Deliverer: %s | Order Owner: %s | Order ID: %s | Item: %s | Delivered: %d | Earned: %.2f | Progress: %d/%d",
                delivererName,
                order.getPlayerName(),
                order.getId(),
                StringUtil.formatMaterialName(order.getMaterial()),
                deliveredAmount,
                earnedMoney,
                order.getDelivered(),
                order.getAmount()
        );
        log(message);
    }

}
