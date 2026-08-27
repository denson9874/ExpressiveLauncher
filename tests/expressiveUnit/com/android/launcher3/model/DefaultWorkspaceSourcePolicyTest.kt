package com.android.launcher3.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultWorkspaceSourcePolicyTest {

    @Test
    fun expressiveProduct_usesBundledPixelStyleLayout() {
        assertThat(ModelDbController.shouldUseExternalDefaultLayout(true)).isFalse()
    }

    @Test
    fun otherProducts_preserveExternalLayoutPriority() {
        assertThat(ModelDbController.shouldUseExternalDefaultLayout(false)).isTrue()
    }
}
