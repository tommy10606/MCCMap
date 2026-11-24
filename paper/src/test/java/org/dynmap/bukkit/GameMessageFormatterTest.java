package org.dynmap.bukkit;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class GameMessageFormatterTest {
    @Test void stylesProgressCommandsAndNumbers(){String text=GameMessageFormatter.format("Run /mccmap fullrender: 25 tiles");assertTrue(text.contains("\u00a7b/mccmap"));assertTrue(text.contains("\u00a7f25"));assertTrue(text.contains("MCCMap"));}
    @Test void distinguishesErrorsPausesAndCompletion(){assertTrue(GameMessageFormatter.format("Render failed").contains("\u00a7c"));assertTrue(GameMessageFormatter.format("Render paused").contains("\u00a7e"));assertTrue(GameMessageFormatter.format("Render finished").contains("\u00a7a"));}
    @Test void existingStatsColorsArePreserved(){assertEquals("\u00a7bStats",GameMessageFormatter.format("\u00a7bStats"));}
}
