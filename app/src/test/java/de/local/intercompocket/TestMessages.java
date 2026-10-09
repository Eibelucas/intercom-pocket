package de.local.intercompocket;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** English UI texts for engine tests, read from the real default resource file (no Android Context needed). */
final class TestMessages extends Messages {
    private static final Map<Integer, String> TEXTS = load();

    TestMessages() {
        super(null);
    }

    @Override
    String get(int id, Object... args) {
        String text = TEXTS.get(id);
        if (text == null) throw new IllegalStateException("No English text for string id " + id);
        return args.length == 0 ? text : String.format(text, args);
    }

    private static Map<Integer, String> load() {
        try {
            String xml =
                    new String(
                            Files.readAllBytes(Path.of("src/main/res/values/strings.xml")),
                            StandardCharsets.UTF_8);
            Map<String, String> byName = new HashMap<>();
            Matcher m =
                    Pattern.compile("<string name=\"([^\"]+)\">(.*?)</string>", Pattern.DOTALL)
                            .matcher(xml);
            while (m.find()) {
                byName.put(
                        m.group(1),
                        m.group(2).replace("\\n", "\n").replace("&amp;", "&"));
            }
            Map<Integer, String> byId = new HashMap<>();
            for (java.lang.reflect.Field field : R.string.class.getFields()) {
                String text = byName.get(field.getName());
                if (text != null) byId.put(field.getInt(null), text);
            }
            return byId;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
