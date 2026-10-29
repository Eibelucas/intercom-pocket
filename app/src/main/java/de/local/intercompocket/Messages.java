package de.local.intercompocket;

import android.content.Context;

import androidx.annotation.StringRes;

/**
 * Localized texts for code that has no Activity (engine, services, telecom callbacks). Unit tests
 * subclass it to supply texts without an Android Context.
 */
class Messages {
    private final Context context;

    Messages(Context context) {
        this.context = context;
    }

    String get(@StringRes int id, Object... args) {
        return args.length == 0 ? context.getString(id) : context.getString(id, args);
    }
}
