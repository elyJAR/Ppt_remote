package com.antigravity.pptremote

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                RemoteScreen(
                    state = state,
                    onBridgeUrlChange = viewModel::setBridgeUrl,
                    onPresentationSelect = viewModel::selectPresentation,
                    onStartSlideshow = viewModel::startSelectedSlideshow,
                    onStopSlideshow = viewModel::stopSelectedSlideshow,
                    onNext = viewModel::nextSlide,
                    onPrevious = viewModel::previousSlide
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                viewModel.nextSlide()
                true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                viewModel.previousSlide()
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }
}

@Composable
private fun RemoteScreen(
    state: RemoteState,
    onBridgeUrlChange: (String) -> Unit,
    onPresentationSelect: (String) -> Unit,
    onStartSlideshow: () -> Unit,
    onStopSlideshow: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("PowerPoint Remote", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = state.bridgeUrl,
            onValueChange = onBridgeUrlChange,
            label = { Text("Desktop Bridge URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "Status: ${state.statusMessage}",
            style = MaterialTheme.typography.bodyMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onStartSlideshow, modifier = Modifier.weight(1f)) {
                Text("Start Slideshow")
            }
            Button(onClick = onStopSlideshow, modifier = Modifier.weight(1f), enabled = !state.isBusy) {
                Text("Stop")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onPrevious, modifier = Modifier.weight(1f), enabled = !state.isBusy) {
                Text("Previous")
            }
            Button(onClick = onNext, modifier = Modifier.weight(1f), enabled = !state.isBusy) {
                Text("Next")
            }
        }

        Text(
            "Use phone volume buttons while this app is open: Volume Up = Next, Volume Down = Previous.",
            style = MaterialTheme.typography.bodySmall
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.presentations, key = { it.id }) { presentation ->
                val selected = presentation.id == state.selectedPresentationId
                PresentationItem(
                    presentation = presentation,
                    selected = selected,
                    onClick = { onPresentationSelect(presentation.id) }
                )
            }
        }
    }
}

@Composable
private fun PresentationItem(presentation: Presentation, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = presentation.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (presentation.inSlideshow) {
                    "Slideshow: slide ${presentation.currentSlide ?: 1}/${presentation.totalSlides}"
                } else {
                    "Editing mode"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(presentation.path, style = MaterialTheme.typography.bodySmall)
        }
    }
}
