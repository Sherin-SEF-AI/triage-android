package ai.deepmost.triage

import ai.deepmost.triage.ui.nav.TriageNavHost
import ai.deepmost.triage.ui.theme.TriageTheme
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TriageApplication).container
        setContent {
            TriageTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = remember { container }
                    TriageNavHost(container = nav)
                }
            }
        }
    }
}
