package dev.gatsyuk.soloranking.feature.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gatsyuk.soloranking.core.data.WorkoutRepository
import dev.gatsyuk.soloranking.core.database.entity.WorkoutEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.gatsyuk.soloranking.core.database.dao.ExerciseDao
import dev.gatsyuk.soloranking.core.database.dao.RoutineDao
import dev.gatsyuk.soloranking.core.database.dao.RoutineWithExercises
import dev.gatsyuk.soloranking.core.database.dao.WorkoutDao
import dev.gatsyuk.soloranking.core.database.dao.WorkoutWithContent
import dev.gatsyuk.soloranking.core.model.formatDurationMillis
import dev.gatsyuk.soloranking.core.model.trackedDurationMillis
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class TrainViewModel @Inject constructor(
    workoutDao: WorkoutDao,
    routineDao: RoutineDao,
    exerciseDao: ExerciseDao,
    private val repository: WorkoutRepository,
) : ViewModel() {

    val history = workoutDao.observeCompletedWorkouts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val routines = routineDao.observeRoutinesWithExercises()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val exerciseNames = exerciseDao.observeExercisesWithMuscles()
        .map { list -> list.associate { it.exercise.id to it.exercise.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** START pressed while a live session with logged sets exists — its fate is the user's call. */
    data class PendingStart(val live: WorkoutEntity, val routineId: Long?)

    private val _pendingStart = MutableStateFlow<PendingStart?>(null)
    val pendingStart: StateFlow<PendingStart?> = _pendingStart

    /** [routineId] null = empty workout. Prompts first if a non-empty live session exists. */
    fun requestStart(routineId: Long?, onStarted: (Long) -> Unit) = viewModelScope.launch {
        val live = repository.nonEmptyLiveWorkout()
        if (live != null) {
            _pendingStart.value = PendingStart(live, routineId)
        } else {
            onStarted(start(routineId))
        }
    }

    fun resolvePendingStart(discardLive: Boolean, onStarted: (Long) -> Unit) = viewModelScope.launch {
        val pending = _pendingStart.value ?: return@launch
        _pendingStart.value = null
        if (discardLive) {
            repository.deleteWorkout(pending.live.id)
        } else {
            repository.finishWorkout(pending.live.id)
        }
        onStarted(start(pending.routineId))
    }

    fun cancelPendingStart() {
        _pendingStart.value = null
    }

    private suspend fun start(routineId: Long?): Long =
        if (routineId == null) repository.startEmptyWorkout() else repository.startFromRoutine(routineId)
}

@Composable
fun TrainScreen(
    onOpenWorkout: (Long) -> Unit,
    onEditRoutine: (Long) -> Unit,
    onNewRoutine: () -> Unit,
    viewModel: TrainViewModel = hiltViewModel(),
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val exerciseNames by viewModel.exerciseNames.collectAsStateWithLifecycle()
    val pendingStart by viewModel.pendingStart.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(0) } // 0 = History, 1 = Routines
    var showNewWorkoutDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (tab == 0) showNewWorkoutDialog = true else onNewRoutine()
            }) {
                Icon(Icons.Default.Add, contentDescription = if (tab == 0) "New workout" else "New routine")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                listOf("History", "Routines").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = tab == index,
                        onClick = { tab = index },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(label) }
                }
            }

            if (tab == 0) {
                HistoryList(history, onOpenWorkout)
            } else {
                RoutineList(
                    routines = routines,
                    exerciseNames = exerciseNames,
                    onStart = { id -> viewModel.requestStart(id) { onOpenWorkout(it) } },
                    onEdit = onEditRoutine,
                )
            }
        }
    }

    if (showNewWorkoutDialog) {
        NewWorkoutDialog(
            routines = routines,
            onDismiss = { showNewWorkoutDialog = false },
            onStartEmpty = {
                showNewWorkoutDialog = false
                viewModel.requestStart(null) { onOpenWorkout(it) }
            },
            onStartRoutine = { id ->
                showNewWorkoutDialog = false
                viewModel.requestStart(id) { onOpenWorkout(it) }
            },
        )
    }

    pendingStart?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelPendingStart,
            title = { Text("Workout in progress") },
            text = {
                Text(
                    "“${pending.live.name}” is still running and has logged sets. " +
                        "What should happen to it?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resolvePendingStart(discardLive = false) { onOpenWorkout(it) }
                }) { Text("Finish it & start new") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.resolvePendingStart(discardLive = true) { onOpenWorkout(it) }
                }) { Text("Discard it", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@Composable
private fun HistoryList(history: List<WorkoutWithContent>, onOpen: (Long) -> Unit) {
    if (history.isEmpty()) {
        EmptyState("No workouts yet", "Hit + to log your first session.")
        return
    }
    val monthFormat = remember { DateTimeFormatter.ofPattern("LLLL yyyy", Locale.ENGLISH) }
    val weekdayFormat = remember { DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var lastMonth: String? = null
        history.forEach { entry ->
            val month = entry.workout.date.format(monthFormat)
            if (month != lastMonth) {
                lastMonth = month
                item(key = "month-$month") {
                    Text(
                        month,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
            }
            item(key = "workout-${entry.workout.id}") {
                Card(onClick = { onOpen(entry.workout.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Date block on the left (reference layout).
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(52.dp),
                        ) {
                            Text(
                                entry.workout.date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                entry.workout.date.format(weekdayFormat).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.workout.name, style = MaterialTheme.typography.titleMedium)
                            val duration = trackedDurationMillis(
                                entry.workout.startTimeEpochMillis,
                                entry.workout.endTimeEpochMillis,
                            )?.let { formatDurationMillis(it) }
                            val setCount = entry.exercises.sumOf { it.sets.size }
                            Text(
                                listOfNotNull(duration, "${entry.exercises.size} exercises", "$setCount sets")
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                entry.exercises.sortedBy { it.workoutExercise.position }
                                    .joinToString(limit = 3) { it.exercise.name },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineList(
    routines: List<RoutineWithExercises>,
    exerciseNames: Map<Long, String>,
    onStart: (Long) -> Unit,
    onEdit: (Long) -> Unit,
) {
    if (routines.isEmpty()) {
        EmptyState("No routines yet", "Hit + to build a template.")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(routines, key = { it.routine.id }) { routine ->
            // Tap the card to edit; START is the only explicit button.
            Card(onClick = { onEdit(routine.routine.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(routine.routine.name, style = MaterialTheme.typography.titleMedium)
                    routine.exercises.sortedBy { it.position }.forEach { entry ->
                        val range = if (entry.repMin != null && entry.repMax != null) {
                            "${entry.targetSets} × ${entry.repMin}-${entry.repMax}"
                        } else {
                            "${entry.targetSets} sets"
                        }
                        Text(
                            "${exerciseNames[entry.exerciseId] ?: "?"} · $range",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = { onStart(routine.routine.id) }) { Text("START") }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewWorkoutDialog(
    routines: List<RoutineWithExercises>,
    onDismiss: () -> Unit,
    onStartEmpty: () -> Unit,
    onStartRoutine: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New workout") },
        text = {
            Column {
                TextButton(onClick = onStartEmpty, modifier = Modifier.fillMaxWidth()) {
                    Text("Empty workout")
                }
                routines.forEach { routine ->
                    TextButton(
                        onClick = { onStartRoutine(routine.routine.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("From: ${routine.routine.name}")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyState(title: String, hint: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
