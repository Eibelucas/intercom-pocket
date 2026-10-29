package de.local.intercompocket;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** English (default) and German resources must define the same names and placeholders. */
public class StringResourcesTest {
    private static final Pattern ENTRY =
            Pattern.compile("<string(?:-array)? name=\"([^\"]+)\">(.*?)</string(?:-array)?>", Pattern.DOTALL);
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[sd]");

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8);
    }

    private static Set<String> names(String xml) {
        Set<String> names = new TreeSet<>();
        Matcher m = ENTRY.matcher(xml);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    @Test
    public void germanAndEnglishDefineTheSameStrings() throws Exception {
        assertEquals(
                names(read("src/main/res/values/strings.xml")),
                names(read("src/main/res/values-de/strings.xml")));
    }

    @Test
    public void placeholdersMatchBetweenLanguages() throws Exception {
        Matcher en = ENTRY.matcher(read("src/main/res/values/strings.xml"));
        String german = read("src/main/res/values-de/strings.xml");
        while (en.find()) {
            Matcher de =
                    Pattern.compile(
                                    "<string(?:-array)? name=\"" + Pattern.quote(en.group(1)) + "\">(.*?)</string(?:-array)?>",
                                    Pattern.DOTALL)
                            .matcher(german);
            if (!de.find()) continue;
            assertEquals(en.group(1), placeholders(en.group(2)), placeholders(de.group(1)));
        }
    }

    private static Set<String> placeholders(String text) {
        Set<String> found = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) found.add(m.group());
        return found;
    }
}
