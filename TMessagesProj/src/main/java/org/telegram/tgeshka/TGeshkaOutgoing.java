/*
 * This file is part of TGeshka, an unofficial client based on Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.tgeshka;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SendMessagesHelper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Hook for SendMessagesHelper: the easter egg (пасхалка) in a new outgoing text message. */
public final class TGeshkaOutgoing {

    private static TGeshkaEasterEgg.Rules rules;

    private TGeshkaOutgoing() {
    }

    /** The message text to send: with the easter egg applied to plain new text, otherwise as it was. */
    public static String messageText(SendMessagesHelper.SendMessageParams params) {
        String message = params.message;
        if (message == null || message.isEmpty() || params.retryMessageObject != null
                || (params.entities != null && !params.entities.isEmpty())) {
            return message;
        }
        return TGeshkaEasterEgg.apply(rules(), message);
    }

    private static synchronized TGeshkaEasterEgg.Rules rules() {
        if (rules == null) {
            try (InputStream input = ApplicationLoader.applicationContext.getAssets().open("tgeshka-easter-egg.txt")) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                }
                rules = TGeshkaEasterEgg.parse(new String(output.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                FileLog.e(e);
                rules = new TGeshkaEasterEgg.Rules();
            }
        }
        return rules;
    }
}
