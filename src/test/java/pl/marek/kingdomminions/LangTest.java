package pl.marek.kingdomminions;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LangTest {
    @Test void bundledCatalogsHaveIdenticalKeysAndArguments() {
        Properties english=Lang.bundled("en");
        for(String language:Lang.supportedLanguages()) {
        Properties polish=Lang.bundled(language);
        assertEquals(english.stringPropertyNames(),polish.stringPropertyNames());
        for(String key:english.stringPropertyNames()) {
            assertFalse(english.getProperty(key).isBlank(),key);
            assertEquals(Lang.placeholders(english.getProperty(key)),Lang.placeholders(polish.getProperty(key)),key);
        }
    }
    }
    @Test void localeSelectsPolishAndFallsBackToEnglish() {
        assertEquals("Różdżka władcy",Lang.text(Locale.forLanguageTag("pl-PL"),"text.001"));
        assertEquals("Ruler's Wand",Lang.text(Locale.UK,"text.001"));
        assertEquals("Ruler's Wand",Lang.text(Locale.GERMAN,"text.001"));
    }
    @Test void everyTemplateFormatsWithoutLeavingMissingPlaceholders() {
        for(String language:Lang.supportedLanguages())for(String key:Lang.bundled(language).stringPropertyNames()) {
            String formatted=Lang.text(Locale.forLanguageTag(language),key,"A","B","C","D","E");
            assertTrue(Lang.placeholders(formatted).isEmpty(),key);
        }
    }
    @Test void formattingPreservesPlayerTextLiterally() {
        assertEquals("Helper renamed to $hero\\{1}'",Lang.text(Locale.ENGLISH,"text.113","$hero\\{1}'"));
    }
}
