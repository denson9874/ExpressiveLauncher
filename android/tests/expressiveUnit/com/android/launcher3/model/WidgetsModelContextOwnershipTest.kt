package com.android.launcher3.model

import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Modifier
import org.junit.Test

class WidgetsModelContextOwnershipTest {

    @Test
    fun context_isOwnedByEachModelInstance() {
        val contextField = WidgetsModel::class.java.getDeclaredField("mContext")

        // Preview and secondary widget models coexist with the launcher model. A static context
        // lets the newest model silently redirect every existing model to the wrong lifecycle.
        assertThat(Modifier.isStatic(contextField.modifiers)).isFalse()
        assertThat(Modifier.isFinal(contextField.modifiers)).isTrue()
    }
}
