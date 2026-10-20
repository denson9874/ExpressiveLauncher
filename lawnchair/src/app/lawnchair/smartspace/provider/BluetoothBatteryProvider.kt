package app.lawnchair.smartspace.provider

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.smartspace.model.SmartspaceScores
import app.lawnchair.smartspace.model.SmartspaceTarget
import app.lawnchair.util.broadcastReceiverFlow
import com.android.launcher3.R
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

data class BluetoothDeviceState(
    val address: String,
    val name: String,
    val batteryLevel: Int,
    val lastUpdated: Long = System.currentTimeMillis(),
)

class BluetoothBatteryProvider(context: Context) :
    SmartspaceDataSource(
        context,
        R.string.smartspace_bt_battery_title,
        { smartspaceBatteryStatus },
    ) {

    private val headphonesIcon = Icon.createWithResource(context, R.drawable.ic_headphones)
    private val batteryLowIcon = Icon.createWithResource(context, R.drawable.ic_battery_low)
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDeviceState>()

    companion object {
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }

    override val internalTargets = broadcastReceiverFlow(
        context,
        IntentFilter().apply {
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        },
    ).map { intent ->
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        if (device != null) {
            val address = safeDeviceAddress(device)
            if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                connectedDevices.remove(address)
            } else {
                val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                val name = safeDeviceName(device)
                if (level in 0..100) {
                    connectedDevices[address] = BluetoothDeviceState(address, name, level)
                } else if (!connectedDevices.containsKey(address)) {
                    // Connected but no battery reported yet; keep placeholder
                }
            }
        }
        selectBestTarget()
    }.onStart {
        emit(emptyList())
    }

    private fun safeDeviceAddress(device: BluetoothDevice): String {
        return try {
            device.address ?: "device-${device.hashCode()}"
        } catch (_: SecurityException) {
            "device-${device.hashCode()}"
        }
    }

    private fun safeDeviceName(device: BluetoothDevice): String {
        return try {
            device.name?.takeIf { it.isNotBlank() } ?: "Bluetooth Device"
        } catch (_: SecurityException) {
            "Bluetooth Device"
        }
    }

    @SuppressLint("MissingPermission")
    private fun selectBestTarget(): List<SmartspaceTarget> {
        if (connectedDevices.isEmpty()) return emptyList()

        // Prioritize devices with critical low battery (<= 20%)
        val criticalDevice = connectedDevices.values.firstOrNull { it.batteryLevel <= 20 }
        val bestDevice = criticalDevice
            ?: connectedDevices.values.maxByOrNull { it.lastUpdated }
            ?: return emptyList()

        val isLow = bestDevice.batteryLevel <= 20
        val icon = if (isLow) batteryLowIcon else headphonesIcon
        val score = if (isLow) SmartspaceScores.SCORE_LOW_BATTERY else SmartspaceScores.SCORE_BATTERY

        return listOf(
            SmartspaceTarget(
                id = "btBattery-${bestDevice.address}",
                headerAction = SmartspaceAction(
                    id = "btBatteryAction-${bestDevice.address}",
                    icon = icon,
                    title = bestDevice.name,
                    subtitle = "${bestDevice.batteryLevel}%",
                ),
                score = score,
                featureType = SmartspaceTarget.FeatureType.FEATURE_WEATHER,
            ),
        )
    }
}
