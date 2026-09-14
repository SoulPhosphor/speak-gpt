package org.teslasoft.assistant.preferences.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.ModelEndpointStateGenerationStore
import org.teslasoft.assistant.preferences.backup.portable.ModelEndpointPortableCodec
import org.teslasoft.assistant.preferences.backup.portable.PortablePackage
import org.teslasoft.assistant.preferences.backup.portable.PortablePackageFormat
import org.teslasoft.assistant.preferences.backup.portable.PortableRecoverySemanticValidator
import org.teslasoft.assistant.preferences.backup.portable.PortableRecoveryWriter
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreCategory

@RunWith(AndroidJUnit4::class)
class PortableRecoveryMatrixInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val testContext: Context get() = InstrumentationRegistry.getInstrumentation().context
    private val created = ArrayList<File>()

    @Before
    fun prepare() {
        context.getSharedPreferences("api_endpoint", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("favorite_models", Context.MODE_PRIVATE).edit().clear().commit()
        providerRoot().deleteRecursively()
    }

    @After
    fun cleanup() {
        created.forEach { it.deleteRecursively() }
        providerRoot().deleteRecursively()
        context.getSharedPreferences("api_endpoint", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("favorite_models", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun currentWriterProducesAReadableAllTwelveCategoryFixtureOnRealFiles() {
        val output = tempFile("all-twelve.sgbak")
        val written = PortableRecoveryWriter.createPackage(
            context,
            output,
            recoverySecret = null,
            passwordBlob = null,
            appVersion = "phase-12.9-test"
        )
        assertTrue(written is PortableRecoveryWriter.Result.Ok)
        assertTrue(output.isFile && output.length() > 0L)

        val inspected = PortablePackage.inspect(output)
        assertTrue(inspected is PortablePackage.InspectResult.Ok)
        val header = PortablePackageFormat.readHeader(output)
        assertTrue(header is PortablePackageFormat.HeaderResult.Ok)
        assertEquals(
            PortablePackageFormat.FORMAT_VERSION,
            (header as PortablePackageFormat.HeaderResult.Ok).header.formatVersion
        )

        val decodeRoot = tempDirectory("all-twelve-decode")
        val decoded = PortablePackage.decodeWithSecret(output, ByteArray(0), decodeRoot)
        assertTrue(decoded is PortablePackage.DecodeResult.Ok)
        decoded as PortablePackage.DecodeResult.Ok
        val artifactRoot = File(decodeRoot, "artifacts").apply { mkdirs() }
        val validated = PortablePackage.validateAndExtract(decoded.innerZip, artifactRoot)
        assertTrue(validated is PortablePackage.ValidateResult.Ok)
        validated as PortablePackage.ValidateResult.Ok
        assertEquals(PortablePackage.MANIFEST_VERSION, validated.manifestVersion)
        assertEquals(PortableRestoreCategory.entries.toSet(), validated.declaredCategories)
        assertEquals(
            PortableRestoreCategory.entries.toSet(),
            validated.categoryRecordCounts.keys
        )

        val semantic = PortableRecoverySemanticValidator.validate(
            context,
            validated.artifacts,
            validated.declaredCategories,
            validated.explicitlyEmptyCategories,
            validated.categoryRecordCounts
        )
        assertTrue(semantic is PortableRecoverySemanticValidator.Result.Valid)
    }

    @Test
    fun endpointPointerInterruptionAlwaysExposesOneCompleteGeneration() {
        val store = ModelEndpointStateGenerationStore.get(context)
        val original = endpointData("Original", "original/model")
        val desired = endpointData("Desired", "desired/model")
        assertTrue(store.install(original))

        val desiredGeneration = store.stage(desired)
        assertNotNull(desiredGeneration)
        assertEquals(original, ModelEndpointStateGenerationStore.get(context).read())

        assertTrue(store.activate(desiredGeneration!!))
        assertEquals(desired, ModelEndpointStateGenerationStore.get(context).read())
    }

    @Test
    fun safPublicationRenamesOnlyAfterTheIncompleteBytesExist() {
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        val finalName = "Recovery-Matrix.sgbak"
        val incompleteName = RecoveryDocumentPublication.incompleteName(finalName)
        File(providerRoot(), incompleteName).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        val incompleteUri = DocumentsContract.buildDocumentUri(
            RecoveryTestDocumentsProvider.AUTHORITY,
            incompleteName
        )

        val published = RecoveryDocumentPublication.finalize(
            context.contentResolver,
            incompleteUri,
            finalName
        )

        assertNotNull(published)
        assertEquals(finalName, DocumentsContract.getDocumentId(published!!))
        assertFalse(File(providerRoot(), incompleteName).exists())
        assertTrue(File(providerRoot(), finalName).isFile)
        val actual = context.contentResolver.openInputStream(published)!!.use { it.readBytes() }
        assertArrayEquals(bytes, actual)
    }

    private fun endpointData(label: String, model: String): ModelEndpointPortableCodec.Data {
        val endpoint = ModelEndpointPortableCodec.Endpoint(
            id = "phase-12-9-endpoint",
            label = label,
            host = "https://example.invalid/v1",
            chatEndpoint = "/chat/completions",
            speechEndpoint = "/audio/speech",
            authType = "bearer",
            model = model,
            temperature = 0.7,
            topP = 1.0,
            frequencyPenalty = 0.0,
            presencePenalty = 0.0,
            maxTokens = 1500,
            endSeparator = "",
            prefix = "",
            provider = "",
            connectTimeoutSeconds = 30,
            responseTimeoutSeconds = 600,
            contextWindowTokens = null,
            contextWindowModelId = "",
            imageCapabilityByModel = "",
            toolCapabilityByModel = "",
            reasoningCapabilityByModel = "",
            reasoningRejectedLevelsByModel = "",
            providerDiscoveryPath = "",
            identity = "generic",
            rejectedTtsVoices = listOf("voice-a")
        )
        return ModelEndpointPortableCodec.Data(
            listOf(endpoint),
            listOf(linkedMapOf("endpointId" to endpoint.id, "modelId" to model))
        )
    }

    private fun providerRoot(): File = File(
        testContext.filesDir,
        RecoveryTestDocumentsProvider.DIRECTORY
    )

    private fun tempFile(name: String): File =
        File(context.cacheDir, "phase-12-9-${System.nanoTime()}-$name").also(created::add)

    private fun tempDirectory(name: String): File =
        File(context.cacheDir, "phase-12-9-${System.nanoTime()}-$name")
            .apply { mkdirs() }
            .also(created::add)
}
