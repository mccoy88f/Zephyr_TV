package link.mccoy88f.zephyr
import android.content.Intent
import android.os.Build
import android.provider.Settings
internal fun MainActivity.openSystemSettings() {
 if(!Build.MANUFACTURER.equals("Amazon",true)) {openSettings(Settings.ACTION_SETTINGS);return}
 if(settingsScrim.visibility!=android.view.View.VISIBLE)showPanel()
 val names=listOf(getString(R.string.firetv_settings_network) to "network.NetworkActivity", getString(R.string.firetv_settings_apps) to "applications.ApplicationsActivity", getString(R.string.firetv_settings_bluetooth) to "controllers_bluetooth_devices.ControllersAndBluetoothActivity", getString(R.string.firetv_settings_device) to "device.DeviceActivity")
 showPage(getString(R.string.settings),names.map { (name,activity) -> page(name) {
  runCatching { startActivity(Intent().setClassName("com.amazon.tv.settings.v2","com.amazon.tv.settings.v2.tv.$activity")) }.onFailure { openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS) }
 } } + page(getString(R.string.trakt_connect)) { traktSignIn(currentUser()) },back={closeLauncherSettings()})
}
