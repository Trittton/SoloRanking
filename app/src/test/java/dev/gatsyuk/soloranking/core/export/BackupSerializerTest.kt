package dev.gatsyuk.soloranking.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSerializerTest {

    private fun sampleSnapshot() = BackupSnapshot(
        exportedAtEpochMillis = 1_783_000_000_000,
        muscleGroups = listOf(MuscleGroupDto(1, "Chest", 0)),
        exercises = listOf(
            ExerciseDto(10, "Barbell Bench Press", 1, "STRENGTH_WEIGHT_REPS", false, false, false),
        ),
        exerciseMuscles = listOf(ExerciseMuscleDto(10, "CHEST", "PRIMARY", 1.0)),
        workouts = listOf(
            WorkoutDto(100, "Push, day", 20645, 1_783_159_200_000, 1_783_162_800_000, 90.0, "felt good", null),
        ),
        workoutExercises = listOf(WorkoutExerciseDto(200, 100, 10, 0, null)),
        setEntries = listOf(
            SetEntryDto(300, 200, 0, "WORKING", 100.0, 5, null, null, null, "top set", true),
            SetEntryDto(301, 200, 1, "WARMUP", 60.0, 8, null, null, null, null, false),
        ),
        foodItems = listOf(
            FoodItemDto(1, "Oats, rolled", "BrandX", "OFF", "123", 100.0, "g", 370.0, 13.0, 60.0, 7.0, true),
        ),
        diaryEntries = listOf(DiaryEntryDto(1, 20645, "BREAKFAST", 1, 0.8)),
        nutritionTargets = listOf(NutritionTargetDto(1, 20645, 2500, 150, 250, 80)),
        preferences = PreferencesDto(
            sex = "MALE",
            bodyweightFallbackKg = 90.0,
            weightUnit = "KG",
            themeMode = "DARK",
            restTimerSeconds = 150,
        ),
    )

    @Test
    fun `json round trip preserves the snapshot exactly`() {
        val original = sampleSnapshot()
        val restored = BackupSerializer.fromJson(BackupSerializer.toJson(original))
        assertEquals(original, restored)
    }

    @Test
    fun `newer backup version is rejected`() {
        val future = BackupSerializer.toJson(sampleSnapshot())
            .replace("\"version\": ${BackupSnapshot.CURRENT_VERSION}", "\"version\": 999")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            BackupSerializer.fromJson(future)
        }
        assertTrue(ex.message!!.contains("newer than this app supports"))
    }

    @Test
    fun `v1 backup without preferences block imports with null preferences`() {
        val v1 = """{"version": 1, "exportedAtEpochMillis": 1783000000000}"""
        val restored = BackupSerializer.fromJson(v1)
        assertEquals(1, restored.version)
        assertEquals(null, restored.preferences)
        assertTrue(restored.workouts.isEmpty())
    }

    @Test
    fun `workouts csv denormalizes sets and quotes commas`() {
        val csv = BackupSerializer.workoutsCsv(sampleSnapshot())
        val lines = csv.trim().lines()
        assertEquals(3, lines.size) // header + 2 sets
        assertTrue(lines[0].startsWith("date,workout,bodyweight_kg,exercise"))
        // workout name contains a comma -> must be quoted
        assertTrue(lines[1].contains("\"Push, day\""))
        assertTrue(lines[1].contains("Barbell Bench Press"))
        assertTrue(lines[1].contains("WORKING"))
    }

    @Test
    fun `nutrition csv computes macros from quantity`() {
        val csv = BackupSerializer.nutritionCsv(sampleSnapshot())
        val lines = csv.trim().lines()
        assertEquals(2, lines.size)
        // 0.8 servings of 370 kcal = 296
        assertTrue(lines[1].contains("296"))
        assertTrue(lines[1].contains("\"Oats, rolled\""))
    }
}
