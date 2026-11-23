package cz.primevoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cz.primevoice.core.NetworkMode
import cz.primevoice.translation.CloudTranslation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CloudPrivacyTest {
    @Test fun keyIsEncryptedAndOfflineNeverEnablesCloud() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cloud = CloudTranslation(context)
        val dummy = "TEST-ONLY-NOT-A-REAL-KEY-123456789"
        try {
            cloud.mode(NetworkMode.OFFLINE_ONLY)
            cloud.configure("test-model", dummy)
            assertFalse(cloud.state.value.consent)
            assertFalse(File(context.noBackupFilesDir,"cloud-key.enc").readBytes().toString(Charsets.ISO_8859_1).contains(dummy))
            cloud.consent(true)
            assertFalse(cloud.available())
            cloud.configure("changed-model",dummy)
            assertFalse("New model/key must obtain consent again", cloud.state.value.consent)
        } finally { cloud.remove(); cloud.mode(NetworkMode.AUTOMATIC) }
        assertFalse(File(context.noBackupFilesDir,"cloud-key.enc").exists())
    }
}
