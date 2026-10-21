package com.dopachiru.core

import com.dopachiru.core.model.Target
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 全指定はシステムの裏方を含まない。名指し・タグなら含む。 */
class TargetSystemTagTest {

    private val backstage = setOf(Target.SYSTEM_TAG)

    @Test
    fun matchAllSkipsBackstageApps() {
        val all = Target(matchAll = true, exceptPackages = setOf("com.example.notes"))
        assertFalse(all.matches("com.google.android.gms", backstage))
        assertTrue(all.matches("com.google.android.youtube", emptySet()))
        assertFalse(all.matches("com.example.notes", emptySet()))
    }

    @Test
    fun namingABackstageAppStillMatches() {
        assertTrue(Target(packages = setOf("com.google.android.gms")).matches("com.google.android.gms", backstage))
        assertTrue(Target(tags = setOf("sns")).matches("com.example.sys", backstage + "sns"))
        // 全指定と名指しを併せたときも、名指しのほうが効く
        assertTrue(
            Target(matchAll = true, packages = setOf("com.google.android.gms"))
                .matches("com.google.android.gms", backstage),
        )
    }
}
