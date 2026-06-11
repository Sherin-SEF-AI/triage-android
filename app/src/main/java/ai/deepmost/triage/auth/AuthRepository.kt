package ai.deepmost.triage.auth

import ai.deepmost.triage.data.Role
import ai.deepmost.triage.data.dao.DriverDao
import ai.deepmost.triage.data.entity.DriverEntity
import ai.deepmost.triage.integrity.Hashing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.UUID

/** The active shift session (who is logged in). */
data class Session(val driverId: String, val name: String, val role: Role)

/**
 * Local PIN auth for shift accountability (NOT bank-grade security, per the brief). Drivers and
 * supervisors are stored with a salted SHA-256 PIN hash. A default supervisor is seeded on first
 * run so the app is usable out of the box; drivers self-register at the login screen.
 */
class AuthRepository(private val driverDao: DriverDao) {

    private val _session = MutableStateFlow<Session?>(null)
    val session: Flow<Session?> = _session.asStateFlow()
    val currentSession: Session? get() = _session.value

    fun observeDrivers(): Flow<List<DriverEntity>> = driverDao.observeAll()

    suspend fun ensureSeed() {
        if (driverDao.count() == 0) {
            createDriver("Supervisor", DEFAULT_SUPERVISOR_PIN, Role.SUPERVISOR)
            Timber.i("Seeded default supervisor (PIN %s) — change it in Settings", DEFAULT_SUPERVISOR_PIN)
        }
    }

    suspend fun createDriver(name: String, pin: String, role: Role = Role.DRIVER): DriverEntity {
        val id = UUID.randomUUID().toString()
        val entity = DriverEntity(
            id = id, name = name.trim(), pinHash = hash(pin, id), role = role,
            createdAt = System.currentTimeMillis()
        )
        driverDao.upsert(entity)
        return entity
    }

    suspend fun login(name: String, pin: String): Session? {
        val driver = driverDao.byName(name.trim()) ?: return null
        if (driver.pinHash != hash(pin, driver.id)) return null
        val s = Session(driver.id, driver.name, driver.role)
        _session.value = s
        Timber.i("Login %s (%s)", driver.name, driver.role)
        return s
    }

    fun logout() { _session.value = null }

    /** Resume a bound driver's session after a successful biometric unlock (PIN bypassed). */
    suspend fun loginByName(name: String): Session? {
        val driver = driverDao.byName(name.trim()) ?: return null
        val s = Session(driver.id, driver.name, driver.role)
        _session.value = s
        Timber.i("Biometric login %s (%s)", driver.name, driver.role)
        return s
    }

    /** Verify any supervisor PIN — used to PIN-gate the Fleet screen from a driver session. */
    suspend fun supervisorUnlock(pin: String): Boolean =
        driverDao.supervisors().any { it.pinHash == hash(pin, it.id) }

    suspend fun changePin(driverId: String, newPin: String) {
        val driver = driverDao.byId(driverId) ?: return
        driverDao.upsert(driver.copy(pinHash = hash(newPin, driverId)))
    }

    private fun hash(pin: String, salt: String): String = Hashing.sha256("$APP_SALT:$salt:$pin")

    companion object {
        private const val APP_SALT = "triage.ai.deepmost.v1"
        const val DEFAULT_SUPERVISOR_PIN = "1234"
    }
}
