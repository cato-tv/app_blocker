package club.cato.app_blocker

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.annotation.NonNull
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import club.cato.app_blocker.service.AppBlockerService
import club.cato.app_blocker.service.ServiceStarter
import club.cato.app_blocker.service.utils.PrefManager
import club.cato.app_blocker.service.worker.WorkerStarter
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.PluginRegistry.Registrar


/** AppBlockerPlugin */
class AppBlockerPlugin: BroadcastReceiver(), MethodCallHandler, FlutterPlugin, ActivityAware, PluginRegistry.NewIntentListener {
  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private lateinit var channel : MethodChannel
  private lateinit var applicationContext: Context
  private var mainActivity: Activity? = null

  private fun onAttachedToEngine(context: Context, binaryMessenger: BinaryMessenger) {
    applicationContext = context
    channel = MethodChannel(binaryMessenger, "club.cato/app_blocker")
    channel.setMethodCallHandler(this)

    val backgroundCallbackChannel = MethodChannel(binaryMessenger, "club.cato/app_blocker_background")
    backgroundCallbackChannel.setMethodCallHandler(this)
    AppBlockerService.setBackgroundChannel(backgroundCallbackChannel)

    val intentFilter = IntentFilter()
    intentFilter.addAction(APP_BLOCKED_EVENT)
    val manager = LocalBroadcastManager.getInstance(applicationContext)
    manager.registerReceiver(this, intentFilter)
  }

  override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    onAttachedToEngine(binding.applicationContext, binding.binaryMessenger);
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    // unregister any resource held here like LocalBroadcastReceiver
    LocalBroadcastManager.getInstance(binding.applicationContext).unregisterReceiver(this)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    binding.addOnNewIntentListener(this)
    mainActivity = binding.activity
  }

  override fun onDetachedFromActivityForConfigChanges() {
    mainActivity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    binding.addOnNewIntentListener(this)
    mainActivity = binding.activity
  }

  override fun onDetachedFromActivity() {
    mainActivity = null
  }


  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    if("AppBlockerService#start" == call.method) {
      var setupCallbackHandle: Long = 0
      var backgroundMessageHandle: Long = 0;
      try {
        val callbacks = call.arguments as Map<String, Long>
        setupCallbackHandle = callbacks["setupHandle"] ?: 0
        backgroundMessageHandle = callbacks["backgroundHandle"] ?: 0
      } catch (e: Exception) {
        e.printStackTrace()
      }
      AppBlockerService.setBackgroundSetupHandle(mainActivity!!, setupCallbackHandle)
      AppBlockerService.startBackgroundIsolate(mainActivity!!, setupCallbackHandle)
      AppBlockerService.setBackgroundMessageHandle(mainActivity!!, backgroundMessageHandle)
      result.success(true)

    } else if("AppBlockerService#initialized" == call.method) {
      AppBlockerService.onInitialized()
      result.success(true)
    } else if("configure" == call.method) {
      // Start services
      ServiceStarter.startService(applicationContext)
      WorkerStarter.startServiceCheckerWorker()
      result.success(true)
    }else if("enableAppBlocker" == call.method) {
      result.success(enableAppBlocker())
    } else if("disableAppBlocker" == call.method) {
      result.success(disableAppBlocker())
    } else if("setTime" == call.method) {
      val startTime = call.argument<String>("starTime")
      val endTime = call.argument<String>("endTime")
      if(startTime == null || endTime == null) {
        result.success(false);
      } else {
        result.success(setRestrictionTime(startTime, endTime))
      }
    } else if("setWeekDays" == call.method) {
      try {
        val weekdays = call.arguments as List<String>
        result.success(setRestrictionWeekDays(weekdays))
      } catch (e: Exception) {
        result.success(false)
      }
    } else if("updateBlockedPackages" == call.method) {
      try {
        val packages = call.arguments as List<String>
        result.success(updateBlockedPackages(packages))
      } catch (e: Exception) {
        result.success(false)
      }
    } else if("getBlockedPackages" == call.method) {
      result.success(getBlockedPackages())
    } else {
      result.notImplemented()
    }
  }

  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return

    sendMessageFromIntent("onResume", intent)
  }

  override fun onNewIntent(intent: Intent): Boolean {
    val res: Boolean = sendMessageFromIntent("onResume", intent)
    if (res && mainActivity != null) {
      mainActivity!!.intent = intent
    }
    return res
  }

  private fun sendMessageFromIntent(method: String, intent: Intent): Boolean {
    if (APP_BLOCKED_EVENT.equals(intent.action)
            || APP_BLOCKED_EVENT.equals(intent.getStringExtra("app_blocked_event"))) {
      val blockedAppPackage = intent.extras?.getString(APP_BLOCKED_VALUE) ?: return false
      channel.invokeMethod(method, blockedAppPackage)
      return true
    }
    return false
  }

  private fun disableAppBlocker(): Boolean {
    if(!::applicationContext.isInitialized) return false
    PrefManager.setAppBlockEnabled(applicationContext, false)
    WorkerStarter.stopServiceCheckerWorker()
    ServiceStarter.stopService(applicationContext)
    return true
  }

  private fun enableAppBlocker(): Boolean {
    if(!::applicationContext.isInitialized) return false
    Log.d("🙏", "AppBlocker State Passed")
    PrefManager.setAppBlockEnabled(applicationContext, true)
    ServiceStarter.startService(applicationContext)
    WorkerStarter.startServiceCheckerWorker()
    return true
  }

  private fun setRestrictionTime(startTime: String, endTime: String): Boolean {
    if(!::applicationContext.isInitialized) return false
    PrefManager.setRestrictionTime(applicationContext, startTime, endTime)
    return true
  }

  private fun setRestrictionWeekDays(weekDays: List<String>): Boolean {
    if(!::applicationContext.isInitialized) return false
    PrefManager.setRestrictionWeekDays(applicationContext, weekDays)
    return true
  }

  private fun updateBlockedPackages(packages: List<String>): Boolean {
    if(!::applicationContext.isInitialized) return false

    PrefManager.updateBlockedPackages(applicationContext, packages)
    return true
  }

  private fun getBlockedPackages(): List<String> {
    if(!::applicationContext.isInitialized) return listOf()
    return PrefManager.getAllBlackListedPackages(applicationContext).toList()
  }


  companion object {

    const val APP_BLOCKED_EVENT = "app_blocked_event"
    const val APP_BLOCKED_VALUE = "app_blocked_package"

    @JvmStatic
    fun registerWith(registrar: Registrar) {
      val instance = AppBlockerPlugin()
      instance.mainActivity = registrar.activity()
      registrar.addNewIntentListener(instance)
      instance.onAttachedToEngine(registrar.context(), registrar.messenger())
    }

    fun getLauncherIntentFromAppContext(context: Context): Intent? {

      val appPackage = context.applicationContext.packageName
      return context.packageManager.getLaunchIntentForPackage(appPackage)
    }
  }
}
