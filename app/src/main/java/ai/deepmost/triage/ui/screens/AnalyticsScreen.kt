package ai.deepmost.triage.ui.screens

import ai.deepmost.triage.R
import ai.deepmost.triage.analytics.FleetKpis
import ai.deepmost.triage.di.AppContainer
import ai.deepmost.triage.ui.common.BarChart
import ai.deepmost.triage.ui.common.BarDatum
import ai.deepmost.triage.ui.common.CardSurface
import ai.deepmost.triage.ui.common.HairlineDivider
import ai.deepmost.triage.ui.common.KeyValue
import ai.deepmost.triage.ui.common.SectionHeader
import ai.deepmost.triage.ui.theme.OkGreen
import ai.deepmost.triage.ui.theme.TextSecondary
import ai.deepmost.triage.ui.theme.TriageAccent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun AnalyticsScreen(container: AppContainer, onBack: () -> Unit) {
    val vehicles by container.vehicleService.observeAll().collectAsState(initial = emptyList())
    var kpis by remember { mutableStateOf<FleetKpis?>(null) }

    LaunchedEffect(vehicles) {
        kpis = container.fleetMetrics.compute(vehicles)
    }

    LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.fleet_kpis), style = MaterialTheme.typography.titleLarge, color = TriageAccent)
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            }
            HairlineDivider(Modifier.padding(vertical = 8.dp))
        }

        val k = kpis
        if (k == null) {
            item { Text(stringResource(R.string.analyzing), color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
        } else {
            item {
                CardSurface {
                    Column {
                        KeyValue("Vehicles", k.vehicles.toString())
                        KeyValue("Finalized inspections", k.finalizedInspections.toString())
                        KeyValue("Open NEW damages", k.openNewDamages.toString())
                        KeyValue("Avg fleet cleanliness", if (k.avgFleetCleanliness.isNaN()) "—" else "${k.avgFleetCleanliness.toInt()}/100")
                    }
                }
            }
            item {
                SectionHeader(stringResource(R.string.damage_rate) + " (open NEW / vehicle)")
                CardSurface {
                    BarChart(
                        data = k.vehicleDamage.take(8).map { BarDatum(it.registration, it.openDamages.toFloat()) },
                        accent = TriageAccent
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.driver_scorecards)) }
            items(k.driverScores) { d ->
                CardSurface(Modifier.padding(vertical = 3.dp)) {
                    Column {
                        Text(d.name, style = MaterialTheme.typography.labelLarge)
                        KeyValue("Inspections", d.inspections.toString())
                        KeyValue("NEW damages introduced", d.newDamagesIntroduced.toString(),
                            valueColor = if (d.newDamagesIntroduced > 0) TriageAccent else OkGreen)
                        KeyValue("Avg cleanliness", if (d.avgCleanliness.isNaN()) "—" else "${d.avgCleanliness.toInt()}/100")
                        KeyValue("Dispute rate", "${(d.disputeRate * 100).toInt()}%")
                    }
                }
            }
        }
    }
}
