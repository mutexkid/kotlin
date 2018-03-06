/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.caches

import com.intellij.testFramework.LightProjectDescriptor
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.idea.test.KotlinLightProjectDescriptor
import org.jetbrains.kotlin.idea.util.sourceRoots

class ImplicitPackagePrefixTest : KotlinLightCodeInsightFixtureTestCase() {
    private lateinit var cacheService: PerModulePackageCacheService

    override fun getProjectDescriptor(): LightProjectDescriptor = KotlinLightProjectDescriptor.INSTANCE

    override fun setUp() {
        super.setUp()
        cacheService = PerModulePackageCacheService.getInstance(myFixture.project)
    }

    private fun prefix(): String {
        return cacheService.getImplicitPackagePrefix(myFixture.module.sourceRoots[0]).asString()
    }

    fun testSimple() {
        myFixture.configureByText("foo.kt", "package com.example.foo")
        assertEquals("com.example.foo", prefix())
    }

    fun testAmbiguous() {
        myFixture.configureByText("foo.kt", "package com.example.foo")
        myFixture.configureByText("bar.kt", "package com.example.bar")
        assertEquals("", prefix())
    }

    fun testUpdateOnCreate() {
        myFixture.configureByText("foo.kt", "package com.example.foo")
        assertEquals("com.example.foo", prefix())
        myFixture.configureByText("bar.kt", "package com.example.bar")
        assertEquals("", prefix())
    }
}
