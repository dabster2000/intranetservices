package dk.trustworks.intranet.aggregates.lunch.services;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@ApplicationScoped
public class MenuService {

    private static final String MENU_STORAGE_PATH = "/var/lunch_menus/";

    @Inject
    EntityManager entityManager;

    public byte[] fetchMenuForWeek(int weekNumber) {
        Path menuFilePath = Paths.get(MENU_STORAGE_PATH, "week-" + weekNumber + ".pdf");

        try {
            return Files.readAllBytes(menuFilePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read the menu file", e);
        }
    }

    public String saveMenuForWeek(int weekNumber, byte[] menuPdf) {
        Path menuFilePath = Paths.get(MENU_STORAGE_PATH, "week-" + weekNumber + ".pdf");

        try {
            Files.write(menuFilePath, menuPdf);
            return "Menu saved successfully!";
        } catch (IOException e) {
            throw new RuntimeException("Failed to save the menu file", e);
        }
    }
}
