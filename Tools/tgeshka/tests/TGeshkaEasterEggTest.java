/*
 * This file is part of TGeshka, an unofficial client based on Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

import org.telegram.tgeshka.TGeshkaEasterEgg;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Unit test of the easter egg (пасхалка): every case of the [tests] section in
 * tgeshka-easter-egg.txt. Runs without Android, see the Java compile job in build.yml.
 */
public class TGeshkaEasterEggTest {

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "tgeshka-easter-egg.txt";
        String content = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        TGeshkaEasterEgg.Rules rules = TGeshkaEasterEgg.parse(content);
        if (rules.names.isEmpty() || rules.insults.isEmpty() || rules.replacement.isEmpty() || rules.tests.isEmpty()) {
            System.out.println("FAIL: empty rules or tests");
            System.exit(1);
        }
        int failed = 0;
        for (String[] test : rules.tests) {
            String output = TGeshkaEasterEgg.apply(rules, test[0]);
            if (!output.equals(test[1])) {
                failed++;
                System.out.println("FAIL: '" + test[0] + "' -> '" + output + "', expected '" + test[1] + "'");
            }
        }
        String plain = "просто текст";
        if (TGeshkaEasterEgg.apply(rules, plain) != plain) {
            failed++;
            System.out.println("FAIL: unchanged text must be the same object");
        }
        System.out.println((rules.tests.size() + 1 - failed) + " of " + (rules.tests.size() + 1) + " cases passed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
