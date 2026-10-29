package de.local.intercompocket;

import android.content.Context;

final class AppInfo {
    private AppInfo() {}

    /** The installed app version (e.g. "2.0.0"); "dev" when it cannot be read, such as in unit tests. */
    static String versionName(Context context) {
        if (context == null) return "dev";
        try {
            String name =
                    context.getPackageManager().getPackageInfo(context.getPackageName(), 0)
                            .versionName;
            return name == null ? "dev" : name;
        } catch (Exception e) {
            return "dev";
        }
    }
}
