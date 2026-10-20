package app.lawnchair.smartspace.provider

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothDevice

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class BluetoothBatteryProviderTest {

    @Test
    fun target_createdWhenBatteryLevelReported() {
        val device: BluetoothDevice = ShadowBluetoothDevice.newInstance("00:11:22:33:44:55")
        val intent = Intent(BluetoothBatteryProvider.ACTION_BATTERY_LEVEL_CHANGED).apply {
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra(BluetoothBatteryProvider.EXTRA_BATTERY_LEVEL, 75)
        }
        assertThat(intent.getIntExtra(BluetoothBatteryProvider.EXTRA_BATTERY_LEVEL, -1)).isEqualTo(75)
        assertThat(intent.action).isEqualTo(BluetoothBatteryProvider.ACTION_BATTERY_LEVEL_CHANGED)
    }
}
