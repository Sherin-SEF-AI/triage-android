package ai.deepmost.triage.ui.screens

import ai.deepmost.triage.R
import ai.deepmost.triage.data.Role
import ai.deepmost.triage.di.AppContainer
import ai.deepmost.triage.ui.theme.TextSecondary
import ai.deepmost.triage.ui.theme.TriageAccent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(container: AppContainer, onLoggedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by container.settingsRepository.settings.collectAsState(initial = container.currentSettings)
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var registering by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("TRIAGE", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, color = TriageAccent)
        Text(stringResource(R.string.login_subtitle), color = TextSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = name, onValueChange = { name = it; error = null },
            label = { Text(stringResource(R.string.driver_name)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().height(64.dp)
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8); error = null },
            label = { Text(stringResource(R.string.pin)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().height(64.dp)
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = TriageAccent, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                if (name.isBlank() || pin.length < 4) { error = "Enter name and 4+ digit PIN"; return@Button }
                scope.launch {
                    if (registering) {
                        container.authRepository.createDriver(name, pin, Role.DRIVER)
                    }
                    val session = container.authRepository.login(name, pin)
                    if (session == null) error = "Invalid name or PIN" else onLoggedIn()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (registering) stringResource(R.string.register_and_login) else stringResource(R.string.login)) }

        val activity = context as? androidx.fragment.app.FragmentActivity
        if (settings.biometricEnabled && settings.lastDriverName.isNotBlank() && activity != null &&
            ai.deepmost.triage.auth.BiometricAuth.isAvailable(context)
        ) {
            OutlinedButton(
                onClick = {
                    ai.deepmost.triage.auth.BiometricAuth.authenticate(
                        activity, "TRIAGE", "Unlock as ${settings.lastDriverName}",
                        onSuccess = { scope.launch { if (container.authRepository.loginByName(settings.lastDriverName) != null) onLoggedIn() } },
                        onError = { error = it }
                    )
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text(stringResource(R.string.biometric_login) + " · ${settings.lastDriverName}") }
        }

        TextButton(onClick = { registering = !registering; error = null }) {
            Text(if (registering) stringResource(R.string.have_account) else stringResource(R.string.new_driver))
        }
        Text(
            stringResource(R.string.default_supervisor_hint),
            color = TextSecondary,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall
        )
    }
}
