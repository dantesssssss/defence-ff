package com.da4a.smartcity.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Who the rescuer is looking for. */
data class VictimProfile(
    val name: String,
    val sex: String,
    val age: Int,
    val bloodType: String,
    val conditions: List<String>,
)

/** Hardcoded for the demo; later it would come from the victim's phone. */
val DemoVictim = VictimProfile(
    name = "Anna Kowalska",
    sex = "Female",
    age = 34,
    bloodType = "A+",
    conditions = listOf("Asthma", "Penicillin allergy"),
)

/** iOS Medical ID-style summary of the person being searched for. */
@Composable
fun MedicalIdCard(profile: VictimProfile, surface: Color, secondary: Color, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("✱ MEDICAL ID", style = MaterialTheme.typography.labelSmall, color = accent)
        Text(profile.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
        Text("${profile.sex} · ${profile.age} · Blood ${profile.bloodType}", style = MaterialTheme.typography.bodyLarge)
        Text(profile.conditions.joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = secondary)
    }
}
