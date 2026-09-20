package org.teslasoft.assistant.preferences.backup.portable

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SqlCipherManifestDependencyTest {
    @Test
    fun descriptiveManifestVersionMatchesPackagedDependency() {
        val dependencyPattern = Regex("sqlcipher-android:([0-9.]+)")
        val dependency = listOf(File("build.gradle"), File("app/build.gradle"))
            .filter(File::isFile)
            .firstNotNullOfOrNull { dependencyPattern.find(it.readText())?.groupValues?.get(1) }
            ?: error("SQLCipher dependency unavailable")
        assertEquals(dependency, PortablePackage.SQLCIPHER_VERSION)
    }
}
