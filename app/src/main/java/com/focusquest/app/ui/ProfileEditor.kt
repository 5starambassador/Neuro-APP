package com.focusquest.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.focusquest.app.ui.theme.FqColors

/**
 * Used both for first-run onboarding (with the intro + disclaimer) and for adding
 * another player profile later.
 */
@Composable
fun ProfileEditorScreen(
    firstRun: Boolean,
    onBack: (() -> Unit)?,
    onSave: (name: String, age: Int, colorIndex: Int) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var ageText by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableIntStateOf(0) }
    val age = ageText.toIntOrNull()
    val valid = name.isNotBlank() && age != null && age in 4..99

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }
        if (firstRun) {
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(FqColors.Primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Psychology, null, tint = Color.White, modifier = Modifier.size(44.dp)) }
            Spacer(Modifier.height(20.dp))
            Text("Welcome to FocusQuest", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Wear an EEG headband and play games that move only when you focus. " +
                    "Short daily sessions help you practise holding your attention.",
                color = FqColors.TextDim,
            )
            Spacer(Modifier.height(28.dp))
            Text("Who's training?", style = MaterialTheme.typography.titleLarge)
        } else {
            Text("New player", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(24) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = ageText,
            onValueChange = { ageText = it.filter(Char::isDigit).take(2) },
            label = { Text("Age") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text("Colour", color = FqColors.TextDim)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FqColors.avatars.forEachIndexed { i, c ->
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(c)
                        .then(if (i == color) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                        .clickable { color = i },
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { onSave(name, age ?: 0, color) },
            enabled = valid,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (firstRun) "Start training" else "Add player") }

        if (firstRun) {
            Spacer(Modifier.height(20.dp))
            Text(DISCLAIMER, color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

const val DISCLAIMER =
    "FocusQuest is an attention-training game. It is not a medical device and does not " +
        "diagnose, treat or cure ADHD or any other condition. Talk to a qualified professional " +
        "about any health concern."
