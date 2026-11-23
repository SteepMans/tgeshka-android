package org.telegram.messenger;

import org.telegram.messenger.regular.BuildConfig;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    // TGeshka: no FCM, Telegram pushes are bound to the official app. Updates
    // come over the background connection through the gateway.
    @Override
    protected PushListenerController.IPushListenerServiceProvider onCreatePushProvider() {
        return new PushListenerController.IPushListenerServiceProvider() {
            @Override
            public boolean hasServices() {
                return false;
            }

            @Override
            public String getLogTitle() {
                return "TGeshka background connection";
            }

            @Override
            public void onRequestPushToken() {
            }

            @Override
            public int getPushType() {
                return PushListenerController.PUSH_TYPE_FIREBASE;
            }
        };
    }
}
