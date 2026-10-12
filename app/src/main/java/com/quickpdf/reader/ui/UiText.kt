package com.quickpdf.reader.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Text produced outside the UI (view models), resolved later with the activity's context so it follows
 * the in-app language. Arguments that are themselves [UiText] are resolved too.
 */
sealed interface UiText {
    fun resolve(context: Context): String

    class Res(@StringRes val id: Int, vararg val args: Any) : UiText {
        override fun resolve(context: Context) = context.getString(id, *resolveArgs(context, args))
    }

    class Plural(@PluralsRes val id: Int, val count: Int, vararg val args: Any) : UiText {
        override fun resolve(context: Context) = context.resources.getQuantityString(id, count, *resolveArgs(context, args))
    }

    private companion object {
        fun resolveArgs(context: Context, args: Array<out Any>): Array<Any> =
            Array(args.size) { i -> args[i].let { if (it is UiText) it.resolve(context) else it } }
    }
}
