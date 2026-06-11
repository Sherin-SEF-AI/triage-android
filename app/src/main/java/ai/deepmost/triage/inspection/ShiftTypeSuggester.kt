package ai.deepmost.triage.inspection

import ai.deepmost.triage.data.InspectionType
import java.util.Calendar

/**
 * Suggests the inspection type from the time of day and the vehicle's recent history. A morning
 * walkaround with no open shift is SHIFT_START; an evening one (or one following a same-day
 * SHIFT_START) is SHIFT_END. The driver can always override.
 */
class ShiftTypeSuggester(
    private val morningStartHour: Int = 4,
    private val eveningStartHour: Int = 16
) {
    fun suggest(nowMillis: Long, lastTypeToday: InspectionType?): InspectionType {
        if (lastTypeToday == InspectionType.SHIFT_START) return InspectionType.SHIFT_END
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return when {
            hour in morningStartHour until eveningStartHour -> InspectionType.SHIFT_START
            else -> InspectionType.SHIFT_END
        }
    }
}
