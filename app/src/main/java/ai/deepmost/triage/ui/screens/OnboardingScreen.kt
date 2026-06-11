package ai.deepmost.triage.ui.screens

import ai.deepmost.triage.R
import ai.deepmost.triage.ui.theme.TextSecondary
import ai.deepmost.triage.ui.theme.TriageAccent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class Slide(val title: String, val body: String)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val slides = listOf(
        Slide("TRIAGE", "Guided shift-start / shift-end vehicle walkarounds that settle \"who scratched it\" — on your phone."),
        Slide("100% on-device", "Damage, tyre, lamp and cleanliness analysis runs locally. A full inspection works in airplane mode; only optional sync uses the network."),
        Slide("Tamper-evident", "Every photo is hashed at capture and chained per vehicle. Each shift is diffed against the last to surface NEW damage with shift attribution.")
    )
    var index by remember { mutableIntStateOf(0) }
    val last = index == slides.lastIndex

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("0${index + 1} / 0${slides.size}", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(24.dp))
        Text(slides[index].title, color = TriageAccent, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(slides[index].body, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(36.dp))
        Button(
            onClick = { if (last) onDone() else index++ },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(stringResource(if (last) R.string.onboarding_start else R.string.onboarding_next)) }
        TextButton(onClick = onDone) { Text(stringResource(R.string.onboarding_skip), color = TextSecondary) }
    }
}
